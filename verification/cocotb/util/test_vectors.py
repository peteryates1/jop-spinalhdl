#!/usr/bin/env python3
"""
Test vector loader for CocoTB tests

Loads test vectors from JSON files and provides utilities
for parsing values and filtering test cases.
"""

import json
from pathlib import Path
from typing import Dict, List, Any, Optional


class TestVectorLoader:
    """Load and parse test vectors from JSON files"""

    def __init__(self, vectors_dir: str = "../test-vectors"):
        """
        Initialize loader

        Args:
            vectors_dir: Path to test-vectors directory (relative to cocotb directory)
        """
        self.vectors_dir = Path(vectors_dir)

    def load(self, module: str) -> Dict[str, Any]:
        """
        Load test vectors for a module

        Args:
            module: Module name (e.g., 'bytecode-fetch')

        Returns:
            Dictionary containing test vectors
        """
        vector_file = self.vectors_dir / "modules" / f"{module}.json"
        with open(vector_file, 'r') as f:
            return json.load(f)

    def get_test_cases(
        self,
        module: str,
        test_type: Optional[str] = None,
        tags: Optional[List[str]] = None,
        enabled_only: bool = True
    ) -> List[Dict]:
        """
        Get filtered test cases

        Args:
            module: Module name
            test_type: Filter by test type (reset, microcode, edge_case, etc.)
            tags: Filter by tags (test must have at least one matching tag)
            enabled_only: Only return enabled test cases

        Returns:
            List of test case dictionaries
        """
        vectors = self.load(module)
        test_cases = vectors['test_cases']

        # Filter by enabled
        if enabled_only:
            test_cases = [tc for tc in test_cases if tc.get('enabled', True)]

        # Filter by type
        if test_type:
            test_cases = [tc for tc in test_cases if tc['type'] == test_type]

        # Filter by tags
        if tags:
            test_cases = [
                tc for tc in test_cases
                if any(tag in tc.get('tags', []) for tag in tags)
            ]

        return test_cases

    def parse_value(self, value_str: str) -> Optional[int]:
        """
        Parse value string to integer

        Args:
            value_str: Value string (e.g., "0xABCD", "0b1010", "1234")

        Returns:
            Integer value or None if don't-care
        """
        if value_str is None:
            return None

        value_str = value_str.strip()

        # Don't care value
        if value_str.startswith('0xX') or value_str.startswith('0XX'):
            return None

        # Hexadecimal
        if value_str.startswith('0x') or value_str.startswith('0X'):
            return int(value_str, 16)

        # Binary
        if value_str.startswith('0b') or value_str.startswith('0B'):
            return int(value_str, 2)

        # Decimal
        return int(value_str, 10)

    def get_module_info(self, module: str) -> Dict[str, Any]:
        """
        Get module metadata

        Args:
            module: Module name

        Returns:
            Dictionary with module, version, description, metadata
        """
        vectors = self.load(module)
        return {
            'module': vectors['module'],
            'version': vectors['version'],
            'description': vectors.get('description'),
            'metadata': vectors.get('metadata', {})
        }
