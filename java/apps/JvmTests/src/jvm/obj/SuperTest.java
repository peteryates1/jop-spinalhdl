package jvm.obj;

import jvm.TestCase;

public class SuperTest extends TestCase {

	public String toString() {
		return "SuperTest";
	}

	public boolean test() {
		boolean ok = true;

		A a = new A();
		B b = new B();
		C c = new C();

		A Da = new A();
		A Db = new B();
		A Dc = new C();

		ok = ok && a.exec() == 2;
		ok = ok && b.exec() == 2 * 3;
		ok = ok && c.exec() == 2 * 3 * 5;

		ok = ok && Da.exec() == 2;
		ok = ok && Db.exec() == 2 * 3;
		ok = ok && Dc.exec() == 2 * 3 * 5;

		return ok;
	}
}

class A {
	int exec() {
		return 2;
	}
}

class B extends A {
	int exec() {
		return super.exec() * 3;
	}
}

class C extends B {
	int exec() {
		return super.exec() * 5;
	}
}
