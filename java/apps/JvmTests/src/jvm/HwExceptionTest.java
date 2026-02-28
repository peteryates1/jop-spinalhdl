package jvm;

import com.jopdesign.sys.Const;
import com.jopdesign.sys.Native;

/**
 * Test exception edge cases: StackOverflowError, IllegalMonitorStateException.
 *
 * T1: throw+catch StackOverflowError (SOE class instantiation + athrow with Error subclass)
 * T2: throw+catch IllegalMonitorStateException (IMSE class instantiation + athrow)
 * T3: Hardware exception path for EXC_MON via IO_EXCPT write
 *     (IO_EXCPT -> BmbSys excPend -> BytecodeFetchStage sys_exc ->
 *      JVMHelp.except() -> throw IMSExc)
 *
 * Note: StackOverflowError hardware detection (EXC_SPOV) is untestable -
 * no hardware writes EXC_SPOV, and JVMHelp.except() resets SP for SPOV
 * which corrupts non-overflowed stacks. T1 only tests throw+catch.
 */
public class HwExceptionTest extends TestCase {

	public String toString() {
		return "HwExceptionTest";
	}

	public boolean test() {
		boolean ok = true;

		// T1: throw + catch StackOverflowError
		boolean caught = false;
		try {
			throw new StackOverflowError();
		} catch (StackOverflowError e) {
			caught = true;
		}
		if (!caught) System.out.print(" T1");
		ok &= caught;

		// T2: throw + catch IllegalMonitorStateException
		caught = false;
		try {
			throw new IllegalMonitorStateException();
		} catch (IllegalMonitorStateException e) {
			caught = true;
		}
		if (!caught) System.out.print(" T2");
		ok &= caught;

		// T3: Hardware exception dispatch for EXC_MON
		// Native.wr writes to IO_EXCPT -> BmbSys sets excPend ->
		// BytecodeFetchStage redirects to sys_exc -> JVMHelp.except()
		// reads EXC_MON -> throws pre-allocated IMSExc
		caught = false;
		try {
			Native.wr(Const.EXC_MON, Const.IO_EXCPT);
		} catch (IllegalMonitorStateException e) {
			caught = true;
		}
		if (!caught) System.out.print(" T3");
		ok &= caught;

		return ok;
	}
}
