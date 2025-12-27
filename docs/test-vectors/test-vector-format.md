# Test Vector Format Specification

## Overview

Test vectors are maintained in JSON format and shared between CocoTB (Python) and ScalaTest (Scala) test suites. This ensures a single source of truth and prevents test drift.

## Directory Structure

```
verification/test-vectors/
├── schema/
│   └── test-vector-schema.json      # JSON schema for validation
├── modules/
│   ├── bytecode-fetch.json
│   ├── microcode-fetch.json
│   ├── microcode-decode.json
│   ├── execute.json
│   └── stack-buffer.json
├── integration/
│   ├── pipeline-integration.json
│   └── full-core.json
└── microcode/
    ├── nop.json
    ├── add.json
    ├── load.json
    └── ...                          # One file per microcode instruction
```

## JSON Schema

```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "title": "JOP Test Vector",
  "type": "object",
  "required": ["module", "version", "test_cases"],
  "properties": {
    "module": {
      "type": "string",
      "description": "Module name (e.g., 'bytecode-fetch', 'execute')"
    },
    "version": {
      "type": "string",
      "description": "Test vector version (semver)"
    },
    "description": {
      "type": "string",
      "description": "Human-readable description of test suite"
    },
    "metadata": {
      "type": "object",
      "properties": {
        "author": {"type": "string"},
        "created": {"type": "string", "format": "date-time"},
        "modified": {"type": "string", "format": "date-time"},
        "tags": {"type": "array", "items": {"type": "string"}}
      }
    },
    "test_cases": {
      "type": "array",
      "items": {"$ref": "#/definitions/test_case"}
    }
  },
  "definitions": {
    "test_case": {
      "type": "object",
      "required": ["name", "type", "cycles"],
      "properties": {
        "name": {
          "type": "string",
          "description": "Unique test case name"
        },
        "type": {
          "type": "string",
          "enum": ["reset", "microcode", "edge_case", "integration", "performance"],
          "description": "Test case category"
        },
        "description": {
          "type": "string",
          "description": "What this test verifies"
        },
        "tags": {
          "type": "array",
          "items": {"type": "string"},
          "description": "Tags for filtering (e.g., ['overflow', 'critical'])"
        },
        "initial_state": {
          "type": "object",
          "description": "Initial register/signal values",
          "additionalProperties": true
        },
        "inputs": {
          "type": "array",
          "items": {
            "type": "object",
            "properties": {
              "cycle": {"type": "integer"},
              "signals": {"type": "object", "additionalProperties": true}
            }
          },
          "description": "Input signals per cycle"
        },
        "expected_outputs": {
          "type": "array",
          "items": {
            "type": "object",
            "properties": {
              "cycle": {"type": "integer"},
              "signals": {"type": "object", "additionalProperties": true}
            }
          },
          "description": "Expected output signals per cycle"
        },
        "expected_state": {
          "type": "object",
          "description": "Expected final register/signal values",
          "additionalProperties": true
        },
        "cycles": {
          "type": "integer",
          "description": "Number of clock cycles for this test",
          "minimum": 1
        },
        "assertions": {
          "type": "array",
          "items": {
            "type": "object",
            "properties": {
              "cycle": {"type": "integer"},
              "signal": {"type": "string"},
              "operator": {"type": "string", "enum": ["==", "!=", "<", ">", "<=", ">="]},
              "value": {},
              "message": {"type": "string"}
            }
          },
          "description": "Custom assertions at specific cycles"
        }
      }
    }
  }
}
```

## Example: Bytecode Fetch Test Vectors

```json
{
  "module": "bytecode-fetch",
  "version": "1.0.0",
  "description": "Test vectors for bytecode fetch stage",
  "metadata": {
    "author": "vhdl-tester",
    "created": "2025-01-15T10:00:00Z",
    "modified": "2025-01-15T10:00:000Z",
    "tags": ["pipeline", "fetch"]
  },
  "test_cases": [
    {
      "name": "reset_clears_state",
      "type": "reset",
      "description": "Verify reset clears all registers",
      "tags": ["reset", "basic"],
      "initial_state": {
        "pc": "0xDEAD",
        "bytecode": "0xBEEF",
        "valid": "0x1"
      },
      "inputs": [
        {
          "cycle": 0,
          "signals": {
            "reset": "0x1"
          }
        },
        {
          "cycle": 1,
          "signals": {
            "reset": "0x0"
          }
        }
      ],
      "expected_state": {
        "pc": "0x0",
        "bytecode": "0x0",
        "valid": "0x0"
      },
      "cycles": 2
    },
    {
      "name": "sequential_fetch",
      "type": "microcode",
      "description": "Fetch sequential bytecode instructions",
      "tags": ["sequential", "basic"],
      "initial_state": {
        "pc": "0x0",
        "valid": "0x0"
      },
      "inputs": [
        {
          "cycle": 1,
          "signals": {
            "enable": "0x1",
            "mem_data": "0x60"
          }
        },
        {
          "cycle": 2,
          "signals": {
            "enable": "0x1",
            "mem_data": "0x61"
          }
        },
        {
          "cycle": 3,
          "signals": {
            "enable": "0x1",
            "mem_data": "0x62"
          }
        }
      ],
      "expected_outputs": [
        {
          "cycle": 1,
          "signals": {
            "bytecode": "0x60",
            "valid": "0x1",
            "pc": "0x1"
          }
        },
        {
          "cycle": 2,
          "signals": {
            "bytecode": "0x61",
            "valid": "0x1",
            "pc": "0x2"
          }
        },
        {
          "cycle": 3,
          "signals": {
            "bytecode": "0x62",
            "valid": "0x1",
            "pc": "0x3"
          }
        }
      ],
      "cycles": 3,
      "assertions": [
        {
          "cycle": 1,
          "signal": "pc",
          "operator": "==",
          "value": "0x1",
          "message": "PC should increment after first fetch"
        }
      ]
    },
    {
      "name": "stall_on_disable",
      "type": "microcode",
      "description": "Verify fetch stalls when disabled",
      "tags": ["stall", "control"],
      "initial_state": {
        "pc": "0x5",
        "bytecode": "0xAB"
      },
      "inputs": [
        {
          "cycle": 1,
          "signals": {
            "enable": "0x0",
            "mem_data": "0x99"
          }
        },
        {
          "cycle": 2,
          "signals": {
            "enable": "0x0",
            "mem_data": "0x88"
          }
        }
      ],
      "expected_state": {
        "pc": "0x5",
        "bytecode": "0xAB"
      },
      "cycles": 2
    },
    {
      "name": "pc_overflow",
      "type": "edge_case",
      "description": "Verify PC wraps at maximum address",
      "tags": ["overflow", "edge_case"],
      "initial_state": {
        "pc": "0xFFFFFFFE"
      },
      "inputs": [
        {
          "cycle": 1,
          "signals": {
            "enable": "0x1",
            "mem_data": "0xAA"
          }
        },
        {
          "cycle": 2,
          "signals": {
            "enable": "0x1",
            "mem_data": "0xBB"
          }
        },
        {
          "cycle": 3,
          "signals": {
            "enable": "0x1",
            "mem_data": "0xCC"
          }
        }
      ],
      "expected_outputs": [
        {
          "cycle": 1,
          "signals": {
            "pc": "0xFFFFFFFF"
          }
        },
        {
          "cycle": 2,
          "signals": {
            "pc": "0x0"
          }
        },
        {
          "cycle": 3,
          "signals": {
            "pc": "0x1"
          }
        }
      ],
      "cycles": 3
    }
  ]
}
```

## Example: Microcode Instruction Test Vectors

```json
{
  "module": "execute",
  "version": "1.0.0",
  "description": "Test vectors for ADD microcode instruction",
  "metadata": {
    "author": "vhdl-tester",
    "created": "2025-01-15T11:00:00Z",
    "tags": ["microcode", "alu", "add"]
  },
  "test_cases": [
    {
      "name": "add_basic",
      "type": "microcode",
      "description": "Basic ADD: TOS + NOS -> TOS, pop stack",
      "tags": ["add", "basic"],
      "initial_state": {
        "tos": "0x0005",
        "nos": "0x0003",
        "sp": "0x02"
      },
      "inputs": [
        {
          "cycle": 1,
          "signals": {
            "microcode": "0x10"
          }
        }
      ],
      "expected_state": {
        "tos": "0x0008",
        "sp": "0x01"
      },
      "cycles": 1
    },
    {
      "name": "add_overflow",
      "type": "edge_case",
      "description": "ADD with overflow wraps",
      "tags": ["add", "overflow"],
      "initial_state": {
        "tos": "0xFFFFFFFF",
        "nos": "0x00000001",
        "sp": "0x02"
      },
      "inputs": [
        {
          "cycle": 1,
          "signals": {
            "microcode": "0x10"
          }
        }
      ],
      "expected_state": {
        "tos": "0x00000000",
        "sp": "0x01"
      },
      "cycles": 1,
      "assertions": [
        {
          "cycle": 1,
          "signal": "tos",
          "operator": "==",
          "value": "0x0",
          "message": "ADD should wrap on overflow"
        }
      ]
    },
    {
      "name": "add_negative",
      "type": "microcode",
      "description": "ADD with negative numbers (two's complement)",
      "tags": ["add", "signed"],
      "initial_state": {
        "tos": "0xFFFFFFFE",
        "nos": "0x00000005",
        "sp": "0x02"
      },
      "inputs": [
        {
          "cycle": 1,
          "signals": {
            "microcode": "0x10"
          }
        }
      ],
      "expected_state": {
        "tos": "0x00000003",
        "sp": "0x01"
      },
      "cycles": 1
    }
  ]
}
```

## Value Format

Values in JSON are strings to preserve exact bit patterns:

- **Hexadecimal**: `"0xABCD"` (preferred for clarity)
- **Decimal**: `"1234"`
- **Binary**: `"0b10101010"` (for bit-level clarity)
- **Don't care**: `"0xXXXX"` or `null` (signal not checked)

## Loading Test Vectors

### Python (CocoTB)

```python
# verification/cocotb/util/test_vectors.py

import json
from pathlib import Path
from typing import Dict, List, Any

class TestVectorLoader:
    """Load and parse test vectors from JSON files"""

    def __init__(self, vectors_dir: Path):
        self.vectors_dir = Path(vectors_dir)

    def load(self, module: str) -> Dict[str, Any]:
        """Load test vectors for a module"""
        vector_file = self.vectors_dir / "modules" / f"{module}.json"
        with open(vector_file, 'r') as f:
            return json.load(f)

    def get_test_cases(self, module: str, test_type: str = None, tags: List[str] = None) -> List[Dict]:
        """Get filtered test cases"""
        vectors = self.load(module)
        test_cases = vectors['test_cases']

        if test_type:
            test_cases = [tc for tc in test_cases if tc['type'] == test_type]

        if tags:
            test_cases = [tc for tc in test_cases
                         if any(tag in tc.get('tags', []) for tag in tags)]

        return test_cases

    def parse_value(self, value_str: str) -> int:
        """Parse value string to integer"""
        if value_str is None or value_str.startswith('0xX'):
            return None  # Don't care

        value_str = value_str.strip()

        if value_str.startswith('0x'):
            return int(value_str, 16)
        elif value_str.startswith('0b'):
            return int(value_str, 2)
        else:
            return int(value_str, 10)
```

### Scala (ScalaTest)

```scala
// verification/scalatest/src/test/scala/jop/util/TestVectorLoader.scala

package jop.util

import play.api.libs.json._
import scala.io.Source
import java.nio.file.{Path, Paths}

case class TestCase(
  name: String,
  testType: String,
  description: Option[String],
  tags: Seq[String],
  initialState: Map[String, String],
  inputs: Seq[CycleInputs],
  expectedOutputs: Seq[CycleOutputs],
  expectedState: Map[String, String],
  cycles: Int,
  assertions: Seq[Assertion]
)

case class CycleInputs(cycle: Int, signals: Map[String, String])
case class CycleOutputs(cycle: Int, signals: Map[String, String])
case class Assertion(cycle: Int, signal: String, operator: String, value: String, message: String)

case class TestVectors(
  module: String,
  version: String,
  description: Option[String],
  testCases: Seq[TestCase]
)

object TestVectorLoader {
  implicit val cycleInputsReads: Reads[CycleInputs] = Json.reads[CycleInputs]
  implicit val cycleOutputsReads: Reads[CycleOutputs] = Json.reads[CycleOutputs]
  implicit val assertionReads: Reads[Assertion] = Json.reads[Assertion]
  implicit val testCaseReads: Reads[TestCase] = Json.reads[TestCase]
  implicit val testVectorsReads: Reads[TestVectors] = Json.reads[TestVectors]

  def load(module: String, vectorsDir: Path = Paths.get("verification/test-vectors")): TestVectors = {
    val vectorFile = vectorsDir.resolve(s"modules/$module.json")
    val source = Source.fromFile(vectorFile.toFile)
    try {
      val json = Json.parse(source.mkString)
      json.as[TestVectors]
    } finally {
      source.close()
    }
  }

  def getTestCases(
    module: String,
    testType: Option[String] = None,
    tags: Seq[String] = Seq.empty
  ): Seq[TestCase] = {
    val vectors = load(module)
    var filtered = vectors.testCases

    testType.foreach { tt =>
      filtered = filtered.filter(_.testType == tt)
    }

    if (tags.nonEmpty) {
      filtered = filtered.filter { tc =>
        tags.exists(tag => tc.tags.contains(tag))
      }
    }

    filtered
  }

  def parseValue(valueStr: String): Option[Int] = {
    if (valueStr == null || valueStr.startsWith("0xX")) {
      None  // Don't care
    } else {
      val trimmed = valueStr.trim
      Some(
        if (trimmed.startsWith("0x")) {
          Integer.parseInt(trimmed.substring(2), 16)
        } else if (trimmed.startsWith("0b")) {
          Integer.parseInt(trimmed.substring(2), 2)
        } else {
          trimmed.toInt
        }
      )
    }
  }
}
```

## Usage Examples

### CocoTB Test

```python
import cocotb
from cocotb.clock import Clock
from cocotb.triggers import RisingEdge
from verification.cocotb.util.test_vectors import TestVectorLoader

# Initialize loader
loader = TestVectorLoader("../../test-vectors")

@cocotb.test()
async def test_from_vectors(dut):
    """Run all test cases from JSON vectors"""
    clock = Clock(dut.clk, 10, units="ns")
    cocotb.start_soon(clock.start())

    test_cases = loader.get_test_cases("bytecode-fetch")

    for tc in test_cases:
        # Setup initial state
        for signal, value in tc['initial_state'].items():
            val = loader.parse_value(value)
            if val is not None:
                getattr(dut, signal).value = val

        # Apply inputs per cycle
        for inp in tc.get('inputs', []):
            await RisingEdge(dut.clk)
            for signal, value in inp['signals'].items():
                val = loader.parse_value(value)
                if val is not None:
                    getattr(dut, signal).value = val

        # Wait for completion
        await RisingEdge(dut.clk)

        # Check expected state
        for signal, value in tc['expected_state'].items():
            expected = loader.parse_value(value)
            if expected is not None:
                actual = int(getattr(dut, signal).value)
                assert actual == expected, \
                    f"{tc['name']}: {signal} = {hex(actual)}, expected {hex(expected)}"
```

### ScalaTest Test

```scala
import org.scalatest.funsuite.AnyFunSuite
import spinal.core.sim._
import jop.util.TestVectorLoader._

class BytcodeFetchSpec extends AnyFunSuite {

  val testCases = getTestCases("bytecode-fetch")

  testCases.foreach { tc =>
    test(tc.name) {
      SimConfig.compile(new BytcodeFetch()).doSim { dut =>
        dut.clockDomain.forkStimulus(period = 10)

        // Setup initial state
        tc.initialState.foreach { case (signal, value) =>
          parseValue(value).foreach { v =>
            // Apply to DUT (signal mapping needed)
          }
        }

        // Apply inputs and verify outputs
        tc.inputs.foreach { inp =>
          dut.clockDomain.waitRisingEdge(1)
          inp.signals.foreach { case (signal, value) =>
            parseValue(value).foreach { v =>
              // Apply input
            }
          }
        }

        // Verify final state
        tc.expectedState.foreach { case (signal, value) =>
          parseValue(value).foreach { expected =>
            // Check output
          }
        }
      }
    }
  }
}
```

## Maintenance Workflow

1. **vhdl-tester creates/updates JSON vectors**
   - Analyze VHDL behavior
   - Create comprehensive test vectors
   - Validate against VHDL

2. **Both test suites consume same vectors**
   - CocoTB loads and runs
   - ScalaTest loads and runs
   - Results are comparable

3. **Reviewer verifies parity**
   - Same vectors used
   - Same results obtained
   - No drift

## Benefits

- ✅ Single source of truth
- ✅ No test drift between implementations
- ✅ Easy to add new test cases
- ✅ Language-agnostic format
- ✅ Version controlled
- ✅ Schema validated
- ✅ Human readable and editable
- ✅ Can be generated programmatically

## Tools

```bash
# Validate test vectors against schema
python tools/validate_vectors.py

# Generate test vectors from VHDL simulation
python tools/capture_vectors.py --module bytecode-fetch

# Compare vector coverage
python tools/compare_vector_coverage.py

# Export vectors to other formats (CSV, XML, etc.)
python tools/export_vectors.py --format csv
```
