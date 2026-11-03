# ---------------------------------------------------------------------------
# EVERY SCALA SOURCE THE GENERATORS ARE BUILT FROM (status item 173).
# Included by quartus.mk, vivado.mk and the ECP5 boards, after PROJECT_ROOT.
#
# A flow re-elaborates when one of these is newer than its stamp. Since
# 2026-11-03 the sbt build has two projects -- generic JOP and its support for
# hardware under spinalhdl/, the hardware layer (boards, parts, PLL builders)
# under hardware/ -- and every flow searched spinalhdl/ alone, in four copies
# of one line: a changed pin or PLL builder would have rebuilt nothing, and
# the build would have succeeded on the previous run's RTL and constraints.
#
# So the search covers every top-level directory with a src/main/scala, which
# is each sbt project's, and a new project needs no edit here.
# .github/scripts/check-generated-deps.sh holds every flow to the tree.
# ---------------------------------------------------------------------------
ifndef JOP_SCALA_MK
JOP_SCALA_MK := 1

SCALA_SRC_DIRS := $(wildcard $(PROJECT_ROOT)/*/src/main/scala)
SCALA_SRC      := $(shell find $(SCALA_SRC_DIRS) -name '*.scala')

endif
