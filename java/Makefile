.PHONY: all tools target apps clean sim-smallest sim-small

all: tools target apps

tools:
	$(MAKE) -C tools/jopa
	$(MAKE) -C tools

target:
	$(MAKE) -C target

apps: tools target
	$(MAKE) -C apps/Smallest
	$(MAKE) -C apps/Small

sim-smallest: all
	java -cp tools/dist/jopsim.jar -Dtrace=2 com.jopdesign.tools.JopSim \
		-link apps/Smallest/HelloWorld.jop.link.txt apps/Smallest/HelloWorld.jop

sim-small: all
	java -cp tools/dist/jopsim.jar -Dtrace=2 com.jopdesign.tools.JopSim \
		-link apps/Small/HelloWorld.jop.link.txt apps/Small/HelloWorld.jop

clean:
	$(MAKE) -C tools/jopa clean
	$(MAKE) -C tools clean
	$(MAKE) -C target clean
	$(MAKE) -C apps/Smallest clean
	$(MAKE) -C apps/Small clean
