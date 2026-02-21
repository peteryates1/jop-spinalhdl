#!/bin/bash
# Record an FPGA test result with SHA256 hashes of input files.
#
# Usage: ./record_test.sh <test_name> <platform> <result> <jop_file> [notes]
#
# Example:
#   ./record_test.sh "QMTECH-GC" "fpga-ep4cgx150" "PASS" "java/apps/Small/HelloWorld.jop" "2000+ rounds"
#   ./record_test.sh "CYC5000-Hello" "fpga-cyc5000" "PASS" "java/apps/Smallest/HelloWorld.jop"

set -euo pipefail

HISTORY_FILE="test-history.tsv"
ROM_FILE="asm/generated/mem_rom.dat"
RAM_FILE="asm/generated/mem_ram.dat"

if [ $# -lt 4 ]; then
    echo "Usage: $0 <test_name> <platform> <result> <jop_file> [notes]"
    echo ""
    echo "Arguments:"
    echo "  test_name  - Name of the test (e.g., QMTECH-GC, CYC5000-Hello)"
    echo "  platform   - Platform identifier (e.g., fpga-ep4cgx150, fpga-cyc5000)"
    echo "  result     - PASS or FAIL"
    echo "  jop_file   - Path to the .jop file used"
    echo "  notes      - Optional notes (e.g., '2000+ rounds')"
    exit 1
fi

TEST_NAME="$1"
PLATFORM="$2"
RESULT="$3"
JOP_FILE="$4"
NOTES="${5:-}"

# Navigate to project root (script is in fpga/scripts/)
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
cd "$PROJECT_ROOT"

sha256_of() {
    if [ -f "$1" ]; then
        sha256sum "$1" | cut -d' ' -f1
    else
        echo "MISSING"
    fi
}

JOP_SHA256=$(sha256_of "$JOP_FILE")
ROM_SHA256=$(sha256_of "$ROM_FILE")
RAM_SHA256=$(sha256_of "$RAM_FILE")

TIMESTAMP=$(date -Iseconds)

# Write header if file is new or empty
if [ ! -s "$HISTORY_FILE" ]; then
    printf 'timestamp\ttest_name\tplatform\tresult\tjop_file\tjop_sha256\trom_sha256\tram_sha256\tnotes\n' >> "$HISTORY_FILE"
fi

printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
    "$TIMESTAMP" "$TEST_NAME" "$PLATFORM" "$RESULT" \
    "$JOP_FILE" "$JOP_SHA256" "$ROM_SHA256" "$RAM_SHA256" "$NOTES" \
    >> "$HISTORY_FILE"

echo "Recorded: $TEST_NAME $PLATFORM $RESULT"
echo "  jop: $JOP_SHA256 ($JOP_FILE)"
echo "  rom: $ROM_SHA256"
echo "  ram: $RAM_SHA256"
echo "  -> $HISTORY_FILE"
