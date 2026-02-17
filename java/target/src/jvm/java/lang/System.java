package java.lang;

import java.io.InputStream;
import java.io.PrintStream;

import com.jopdesign.io.JOPInputStream;
import com.jopdesign.io.JOPOutputStream;
import com.jopdesign.sys.Native;
import com.jopdesign.sys.Startup;

public class System {
	
	public static final InputStream in = new JOPInputStream();
	public static final PrintStream out = new PrintStream(new JOPOutputStream());
	public static final PrintStream err = out;
	
	public static final int OFF_MTAB_ALEN = 1;
	
	public static void exit(int i) {
		Startup.exit();
	}

	public static void arraycopy(Object src, int srcOffset, Object dst, int dstOffset, int length) {

		long srcEnd, dstEnd;

		if ((src == null) || (dst == null)) {
			throw new NullPointerException();
		}

		srcEnd = length + srcOffset;
		dstEnd = length + dstOffset;

		int srcHandle = Native.toInt(src);
		int dstHandle = Native.toInt(dst);

		// the type field from the handle - see GC.java
//		int src_type = Native.rdMem(srcHandle + GC.OFF_TYPE);
//		int dst_type = Native.rdMem(dstHandle + GC.OFF_TYPE);
//
//		// 0 means it's a plain object
//		if (src_type == 0 || dst_type == 0) {
//			throw new ArrayStoreException();
//		}
//		// should be the same, right?
//		if (src_type != dst_type) {
//			throw new ArrayStoreException();
//		}
		// TODO: should we check the object types?

		// TODO: synchronized with GC
//		synchronized (GC.getMutex()) {
			int srcPtr = Native.rdMem(srcHandle);
			int dstPtr = Native.rdMem(dstHandle);

			int srcLen = Native.rdMem(srcHandle + /*GC.*/OFF_MTAB_ALEN);
			int dstLen = Native.rdMem(dstHandle + /*GC.*/OFF_MTAB_ALEN);
			if ((srcOffset < 0) || (dstOffset < 0) || (length < 0) || (srcEnd > srcLen) || (dstEnd > dstLen))
				throw new IndexOutOfBoundsException();

			if (src == dst && srcOffset < dstOffset) {
				for (int i = length - 1; i >= 0; --i) {
					Native.wrMem(Native.rdMem(srcPtr + srcOffset + i), dstPtr + dstOffset + i);
				}
			} else {
				for (int i = 0; i < length; i++) {
					Native.wrMem(Native.rdMem(srcPtr + srcOffset + i), dstPtr + dstOffset + i);
				}
			}
//		}
	}
}
