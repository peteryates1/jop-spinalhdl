# Cleaning, and deliberately nothing else.
#
# There is no top-level build here: each board and each flow is driven from its
# own directory (see README.md and docs/build-structure.md). This file exists
# because `make clean` at the repo root is the first thing anyone tries, and
# because everything generated now lives under one directory, which makes
# cleaning a one-liner rather than a tour of eleven board directories.

BUILD := build

.PHONY: clean mostlyclean help

## clean — remove EVERYTHING generated, including the vendor IP
#
# Costs a MIG/clock-wizard regeneration on the Xilinx boards, a few minutes per
# core, and a Quartus rebuild on the Altera ones. That is the point: `clean`
# should mean clean, and everything under build/ is reproducible from what is
# tracked. If it is not reproducible, it does not belong under build/.
clean:
	rm -rf $(BUILD)
	@echo "removed $(BUILD)/ — next build regenerates IP, microcode, RTL and images"

## mostlyclean — everything except the generated vendor IP
#
# `mostlyclean` is the GNU convention for exactly this: "like clean, but may
# refrain from deleting a few files that people normally don't want to
# recompile". Here that is build/ip/, the MIG and clock-wizard cores, which are
# slow to regenerate and change only when their tracked .prj inputs do.
#
# Use this for a normal from-scratch rebuild; use `clean` when you want to
# prove the IP still regenerates, which is worth doing after touching a .prj or
# an ip-generation script.
mostlyclean:
	@test -d $(BUILD) || { echo "nothing to clean"; exit 0; }
	@find $(BUILD) -mindepth 1 -maxdepth 1 ! -name ip -exec rm -rf {} +
	@echo "removed $(BUILD)/* except $(BUILD)/ip — vendor IP kept"

help:
	@echo "make clean        remove everything generated, including vendor IP"
	@echo "make mostlyclean  the same, but keep build/ip (slow to regenerate)"
	@echo
	@echo "Builds run from a board directory, not here — see README.md."
