package jvm.obj;

import jvm.TestCase;

public class InstanceOfTest extends TestCase {

	public String toString() {
		return "InstanceOfTest";
	}

	public boolean test() {
		boolean ok = true;

		IoA a = new IoA();
		IoB b = new IoB();
		IoC c = new IoC();
		IoD d = new IoD();
		IoE e = new IoE();

		IoA Da = new IoA();
		IoA Db = new IoB();
		IoA Dc = new IoC();
		IoA Dd = new IoD();
		IoE De = new IoE();

		ok = ok && a.exec() == 2;
		ok = ok && b.exec() == 2 * 3;
		ok = ok && c.exec() == 2 * 3 * 5;
		ok = ok && d.exec() == 2 * 7;
		ok = ok && e.noexec() == 13;

		ok = ok && a instanceof IoA;
		ok = ok && b instanceof IoA;
		ok = ok && c instanceof IoA;
		ok = ok && b instanceof IoB;
		ok = ok && c instanceof IoB;
		ok = ok && c instanceof IoC;
		ok = ok && d instanceof IoA;
		ok = ok && e instanceof IoE;

		ok = ok && !(a instanceof IoB);
		ok = ok && !(b instanceof IoC);

		ok = ok && Da.exec() == 2;
		ok = ok && Db.exec() == 2 * 3;
		ok = ok && Dc.exec() == 2 * 3 * 5;
		ok = ok && Dd.exec() == 2 * 7;
		ok = ok && De.noexec() == 13;

		ok = ok && Da instanceof IoA;
		ok = ok && Db instanceof IoA;
		ok = ok && Dc instanceof IoA;
		ok = ok && Db instanceof IoB;
		ok = ok && Dc instanceof IoB;
		ok = ok && Dc instanceof IoC;
		ok = ok && Dd instanceof IoA;
		ok = ok && De instanceof IoE;
		ok = ok && !(Da instanceof IoB);
		ok = ok && !(Db instanceof IoC);
		ok = ok && !(Dc instanceof IoD);
		ok = ok && !(Dd instanceof IoB);

		return ok;
	}
}

class IoA {
	int exec() { return 2; }
}

class IoB extends IoA {
	int exec() { return super.exec() * 3; }
}

class IoC extends IoB {
	int exec() { return super.exec() * 5; }
}

class IoD extends IoA {
	int exec() { return super.exec() * 7; }
}

class IoE {
	int noexec() { return 13; }
}
