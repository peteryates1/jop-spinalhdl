#!/usr/bin/env python3
"""
Validate test vectors against JSON schema
"""

import json
import sys
from pathlib import Path
from typing import List, Tuple
import jsonschema
from jsonschema import validate, ValidationError


class VectorValidator:
    """Validate test vector JSON files against schema"""

    def __init__(self, schema_path: Path):
        with open(schema_path, 'r') as f:
            self.schema = json.load(f)

    def validate_file(self, vector_file: Path) -> Tuple[bool, List[str]]:
        """
        Validate a single test vector file

        Returns:
            (is_valid, errors)
        """
        errors = []

        try:
            with open(vector_file, 'r') as f:
                vectors = json.load(f)

            # Validate against schema
            validate(instance=vectors, schema=self.schema)

            # Additional semantic checks
            errors.extend(self._check_semantics(vectors, vector_file))

        except json.JSONDecodeError as e:
            errors.append(f"Invalid JSON: {e}")
            return False, errors
        except ValidationError as e:
            errors.append(f"Schema validation failed: {e.message}")
            return False, errors
        except FileNotFoundError:
            errors.append(f"File not found: {vector_file}")
            return False, errors

        return len(errors) == 0, errors

    def _check_semantics(self, vectors: dict, file_path: Path) -> List[str]:
        """Perform semantic validation beyond schema"""
        errors = []

        # Check test case names are unique
        names = [tc['name'] for tc in vectors['test_cases']]
        duplicates = [name for name in names if names.count(name) > 1]
        if duplicates:
            errors.append(f"Duplicate test case names: {set(duplicates)}")

        # Check each test case
        for tc in vectors['test_cases']:
            tc_name = tc['name']

            # Verify cycle numbers are consistent
            max_input_cycle = max([inp['cycle'] for inp in tc.get('inputs', [])], default=0)
            max_output_cycle = max([out['cycle'] for out in tc.get('expected_outputs', [])], default=0)
            max_assertion_cycle = max([a['cycle'] for a in tc.get('assertions', [])], default=0)

            max_cycle = max(max_input_cycle, max_output_cycle, max_assertion_cycle)
            if max_cycle >= tc['cycles']:
                errors.append(
                    f"Test '{tc_name}': cycle {max_cycle} exceeds total cycles {tc['cycles']}"
                )

            # Check value formats
            for state_dict in [tc.get('initial_state', {}), tc.get('expected_state', {})]:
                for signal, value in state_dict.items():
                    if not self._is_valid_value(value):
                        errors.append(
                            f"Test '{tc_name}': invalid value format for {signal}: {value}"
                        )

            # Check input/output value formats
            for inp in tc.get('inputs', []):
                for signal, value in inp['signals'].items():
                    if not self._is_valid_value(value):
                        errors.append(
                            f"Test '{tc_name}': invalid input value format for {signal} at cycle {inp['cycle']}: {value}"
                        )

            for out in tc.get('expected_outputs', []):
                for signal, value in out['signals'].items():
                    if not self._is_valid_value(value):
                        errors.append(
                            f"Test '{tc_name}': invalid output value format for {signal} at cycle {out['cycle']}: {value}"
                        )

        return errors

    def _is_valid_value(self, value: str) -> bool:
        """Check if value string is in valid format"""
        if value is None:
            return True

        value = value.strip()

        # Check hex format
        if value.startswith('0x') or value.startswith('0X'):
            hex_part = value[2:]
            # Allow 'X' for don't care
            return all(c in '0123456789ABCDEFabcdefXx' for c in hex_part)

        # Check binary format
        if value.startswith('0b') or value.startswith('0B'):
            bin_part = value[2:]
            return all(c in '01Xx' for c in bin_part)

        # Check decimal format
        try:
            int(value)
            return True
        except ValueError:
            return False


def main():
    """Main entry point"""
    import argparse

    parser = argparse.ArgumentParser(description='Validate JOP test vectors')
    parser.add_argument(
        '--schema',
        type=Path,
        default=Path('verification/test-vectors/schema/test-vector-schema.json'),
        help='Path to schema file'
    )
    parser.add_argument(
        '--vectors-dir',
        type=Path,
        default=Path('verification/test-vectors/modules'),
        help='Directory containing test vector files'
    )
    parser.add_argument(
        'files',
        nargs='*',
        type=Path,
        help='Specific files to validate (default: all in vectors-dir)'
    )
    parser.add_argument(
        '--verbose',
        '-v',
        action='store_true',
        help='Verbose output'
    )

    args = parser.parse_args()

    # Create validator
    try:
        validator = VectorValidator(args.schema)
    except Exception as e:
        print(f"Error loading schema: {e}", file=sys.stderr)
        return 1

    # Determine files to validate
    if args.files:
        vector_files = args.files
    else:
        vector_files = list(args.vectors_dir.glob('*.json'))

    if not vector_files:
        print("No vector files found to validate", file=sys.stderr)
        return 1

    # Validate each file
    all_valid = True
    for vector_file in vector_files:
        is_valid, errors = validator.validate_file(vector_file)

        if is_valid:
            if args.verbose:
                print(f"✓ {vector_file.name}: VALID")
        else:
            print(f"✗ {vector_file.name}: INVALID")
            for error in errors:
                print(f"  - {error}")
            all_valid = False

    # Summary
    if all_valid:
        print(f"\n✓ All {len(vector_files)} files valid")
        return 0
    else:
        print(f"\n✗ Some files failed validation")
        return 1


if __name__ == '__main__':
    sys.exit(main())
