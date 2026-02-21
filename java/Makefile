.PHONY: all tools runtime apps clean sim-smallest sim-small

all: tools runtime apps

tools:
	$(MAKE) -C tools

runtime:
	$(MAKE) -C runtime

apps: tools runtime
	$(MAKE) -C apps/Smallest
	$(MAKE) -C apps/Small

sim-smallest: all
	java -cp tools/dist/jopsim.jar -Dtrace=2 com.jopdesign.tools.JopSim \
		-link apps/Smallest/HelloWorld.jop.link.txt apps/Smallest/HelloWorld.jop

sim-small: all
	java -cp tools/dist/jopsim.jar -Dtrace=2 com.jopdesign.tools.JopSim \
		-link apps/Small/HelloWorld.jop.link.txt apps/Small/HelloWorld.jop

clean:
	$(MAKE) -C tools clean
	$(MAKE) -C runtime clean
	$(MAKE) -C apps/Smallest clean
	$(MAKE) -C apps/Small clean
