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

## cold-check — the one-minute test that catches build-wiring regressions
#
# Clones HEAD to a temp directory and runs `generate` for one board per
# toolchain. No Quartus, no Vivado, no nextpnr -- generation only, which is
# where this whole class of bug actually surfaces.
#
# WHY THIS EXISTS. Relocating generated output has two halves, the producer and
# every consumer, and missing the consumer is invisible in a warm tree because
# the artefact is already there. Five regressions on 2026-08-29/30 were all this
# shape, and every one of them would have failed HERE, in the minute it was
# introduced, instead of hours later in CI or in front of a newcomer:
#
#   deleted dram_pll.vhd, a generator INPUT mistaken for build output
#   JOP_APP_FILE broke the standalone tops
#   wukong `all` missing its create-ip prerequisite
#   GEN_MAIN tested before its ?= default, so the .jop was never built
#   sim logs moved to a directory nothing created
#
# IT CHECKS ARTEFACTS, NOT EXIT CODES, and that distinction is the whole point.
# `generate` SUCCEEDS when the .jop prerequisite is missing -- nothing fails,
# the file simply is not created, and the failure surfaces much later at
# `download`. Proven by reintroducing the GEN_MAIN bug: an exit-code-only
# cold-check reported "clean". So each board asserts the files its flow is
# supposed to have produced.
#
# Run it after ANY change to fpga/*.mk, a board Makefile, build.sbt, or
# anything that moves generated output. It is not a substitute for CI; it is
# the check that makes CI boring.
COLD_DIR ?= /tmp/jop-cold-check

.PHONY: cold-check
cold-check:
	@rm -rf $(COLD_DIR)
	@git clone -q . $(COLD_DIR)
	@# TEST THE WORKING TREE, NOT JUST HEAD. `git clone` takes committed state
	@# only, so the first version of this silently tested the wrong thing --
	@# an uncommitted fix looked broken and an uncommitted BREAKAGE would look
	@# fine, which is the opposite of what a pre-commit check is for.
	@if ! git diff --quiet HEAD; then \
	  git diff HEAD | git -C $(COLD_DIR) apply - && \
	  echo "cold-check: HEAD + uncommitted changes"; \
	else echo "cold-check: $$(git -C $(COLD_DIR) log --oneline -1)"; fi
	@# Untracked files are NOT carried over -- a new file that nothing tracks
	@# will not exist in the clone, exactly as it would not for anyone else.
	@u=$$(git ls-files --others --exclude-standard | head -3); \
	 if [ -n "$$u" ]; then echo "  note: untracked files not included:"; \
	   echo "$$u" | sed 's/^/    /'; fi
	@fail=0; \
	for b in "cyc5000-sdram:generate:cyc5000Serial" \
	         "alchitry-au:generate:auSerial" \
	         "colorlight-i5:generate:colorlightI5Sdram"; do \
	  board=$${b%%:*}; rest=$${b#*:}; tgt=$${rest%%:*}; cfg=$${rest##*:}; \
	  printf '  %-22s ' "$$board"; \
	  if ! $(MAKE) -C $(COLD_DIR)/fpga/$$board $$tgt >$(COLD_DIR)/$$board.log 2>&1; then \
	    echo "FAILED to generate — $(COLD_DIR)/$$board.log"; fail=1; continue; fi; \
	  miss=""; \
	  ls $(COLD_DIR)/build/$$cfg/rtl/*.v >/dev/null 2>&1 || miss="$$miss rtl/*.v"; \
	  ls $(COLD_DIR)/build/microcode/serial/mem_rom.dat >/dev/null 2>&1 || miss="$$miss microcode"; \
	  ls $(COLD_DIR)/build/$$cfg/java/apps/Smallest/HelloWorld.jop >/dev/null 2>&1 || miss="$$miss java/.../HelloWorld.jop"; \
	  if [ -n "$$miss" ]; then echo "generated, but MISSING:$$miss"; fail=1; \
	    else echo "ok"; fi; \
	done; \
	if [ $$fail -eq 0 ]; then echo "cold-check: clean"; \
	  else echo "cold-check: FAILURES above"; exit 1; fi

# Structural checks on the build graph, in seconds and with no toolchain: they
# read make's own rule database and run the guards, rather than building
# anything. Each guards a defect that let the flow SUCCEED while being wrong:
# constraints that never regenerated, a baud nobody chose, and a stray code
# fence that inverted 2,300 lines of a document without changing its source.
.PHONY: check-build
check-build:
	@.github/scripts/check-generated-deps.sh
	@.github/scripts/check-console-baud.sh
	@.github/scripts/check-generator-fallbacks.sh
	@.github/scripts/check-docs-structure.sh

help:
	@echo "make check-build  assert the build graph's guards hold (seconds)"
	@echo "make cold-check   clone HEAD to a temp dir and generate on 3 boards (~1 min)"
	@echo "make clean        remove everything generated, including vendor IP"
	@echo "make mostlyclean  the same, but keep build/ip (slow to regenerate)"
	@echo
	@echo "Builds run from a board directory, not here — see README.md."
