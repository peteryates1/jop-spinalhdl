.PHONY: all tools target apps clean

all: tools target apps

tools:
	$(MAKE) -C tools/jopa
	$(MAKE) -C tools

target:
	$(MAKE) -C target

apps: tools target
	$(MAKE) -C apps/Smallest
	$(MAKE) -C apps/Small

clean:
	$(MAKE) -C tools/jopa clean
	$(MAKE) -C tools clean
	$(MAKE) -C target clean
	$(MAKE) -C apps/Smallest clean
	$(MAKE) -C apps/Small clean
