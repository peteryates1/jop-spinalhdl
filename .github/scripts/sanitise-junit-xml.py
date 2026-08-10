#!/usr/bin/env python3
"""
Make ScalaTest's JUnit XML safe for dorny/test-reporter.

WHY THIS EXISTS
---------------
ScalaTest's `-u` writer emits XML that is valid often enough to pass locally and
fail on a runner. Three separate breakages have come from it, each turning a
fully green build red in the REPORTING step while every test passed:

  1. The <properties> block dumps all ~64 JVM system properties unescaped on one
     line; line.separator's value is a literal newline inside an attribute.
  2. "Invalid attribute name" at a line inside that block — the values differ
     between runners, so it reproduces on CI and not locally.
  3. "Invalid XML" on TEST-jop.pipeline.BytecodeFetchStageTest.xml even with the
     properties block stripped. That file is the one carrying <system-out>/
     <system-err> CDATA; those are empty locally but not on a runner, and test
     output containing "]]>" or a non-UTF-8 byte breaks the CDATA section.

Chasing each escaping bug one at a time has not converged. This script instead
guarantees the output is well formed BY CONSTRUCTION: it parses, and if it
cannot parse, it degrades to a minimal valid file that still carries the counts
rather than handing the reporter something broken.

Genuine test failures are NOT hidden by any of this: sbt exits non-zero on a
failed test and the "Run ..." step fails the job before the reporter runs. This
only stops a presentation layer from vetoing a passing build.
"""

import glob
import re
import sys
import xml.etree.ElementTree as ET

# XML 1.0 forbids most C0 controls even escaped; ScalaTest passes them through
# from captured test output.
CTRL = re.compile(rb'[\x00-\x08\x0b\x0c\x0e-\x1f]')
PROPS = re.compile(rb'<properties>.*?</properties>', re.S)
# Attributes of the opening <testsuite> tag, used to rebuild a stub if the file
# is unparseable. Tolerates ScalaTest's newline-before-first-attribute style.
SUITE_OPEN = re.compile(rb'<testsuite\s([^>]*?)>', re.S)
ATTR = re.compile(rb'(\w[\w.\-]*)\s*=\s*"([^"]*)"')


def stub_from(raw, path):
    """Build a minimal, definitely-valid testsuite carrying whatever counts we
    can still recover. Better than dropping the file: the report keeps showing
    how many tests ran."""
    m = SUITE_OPEN.search(raw)
    attrs = dict(ATTR.findall(m.group(1))) if m else {}

    def get(k, default=b'0'):
        return attrs.get(k, default).decode('utf-8', 'replace')

    name = get(b'name', path.encode()).replace('"', "'")
    suite = ET.Element('testsuite', {
        'name': name,
        'tests': get(b'tests'),
        'failures': get(b'failures'),
        'errors': get(b'errors'),
        'time': get(b'time'),
    })
    # Say so in the report itself, so a reader is not left wondering why the
    # per-test detail vanished for one suite.
    out = ET.SubElement(suite, 'system-out')
    out.text = ('JUnit XML for this suite was not well formed and has been '
                'reduced to counts by .github/scripts/sanitise-junit-xml.py')
    return ET.tostring(suite, encoding='utf-8', xml_declaration=True)


def main():
    files = sorted(glob.glob('target/test-reports/**/*.xml', recursive=True))
    if not files:
        print('sanitise: no XML found under target/test-reports')
        return 0

    for path in files:
        with open(path, 'rb') as fh:
            raw = fh.read()

        cleaned = PROPS.sub(b'<properties/>', raw)
        cleaned = CTRL.sub(b'', cleaned)

        try:
            # Round-trip through a real parser: whatever comes out is well
            # formed, whatever ScalaTest's quoting did on the way in.
            root = ET.fromstring(cleaned.decode('utf-8', 'replace'))
            final = ET.tostring(root, encoding='utf-8', xml_declaration=True)
            note = 'ok'
        except ET.ParseError as exc:
            final = stub_from(raw, path)
            note = f'UNPARSEABLE ({exc}) -> counts-only stub'

        if final != raw:
            with open(path, 'wb') as fh:
                fh.write(final)
        print(f'sanitise: {path} [{note}]')

    return 0


if __name__ == '__main__':
    sys.exit(main())
