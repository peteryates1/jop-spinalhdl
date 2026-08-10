#!/usr/bin/env python3
"""
Make ScalaTest's JUnit XML safe for dorny/test-reporter.

WHY THIS EXISTS
---------------
Reporting-only breakages from the JUnit XML kept turning fully green builds red.
The ROOT CAUSE is now known, and it was never an escaping bug:

  TWO writers were producing target/test-reports/TEST-<suite>.xml. The uploaded
  originals from run 31369568424 show 19 files in ScalaTest's `-u` format and 6
  in another (single-quoted XML declaration, `skipped=` attribute, hostname
  first, 10-space indent). Where they collided, one wrote over the other from
  offset 0 WITHOUT truncating; being shorter it left the previous file's tail
  behind, giving a complete </testsuite> followed by the middle of a line from
  the other format — "junk after document element", one </testsuite> too many.

  It only happened on CI because only CI has the second writer: the runner
  installs a newer sbt (2.0.6 launcher) than project/build.properties pins
  (1.9.7) or local dev uses. Same testOnly command locally produces the same 25
  files with 0 malformed.

The real fix is directory isolation — ScalaTest's -u now writes to
target/scalatest-reports, which nothing else touches. This script stays as a
belt-and-braces guard: it round-trips every file through a real parser and, if
one cannot be parsed, degrades it to a counts-only stub rather than handing the
reporter something broken. If a malformed file ever appears again, the artifact
it uploads is what identified the cause the first time.

Genuine test failures are NOT hidden by any of this: sbt exits non-zero on a
failed test and the "Run ..." step fails the job before the reporter runs. This
only stops a presentation layer from vetoing a passing build.
"""

import glob
import os
import re
import shutil
import sys
import xml.etree.ElementTree as ET

# Pristine copies of every input, taken BEFORE anything is rewritten. This
# script edits in place, so without it the artifact uploaded when the reporter
# fails would contain only the sanitised output — valid XML, and therefore
# useless for working out what the reporter actually choked on.
REPORT_DIR = 'target/scalatest-reports'
RAW_DIR = 'target/scalatest-reports-raw'

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


def emit_output(stubbed):
    """Report how many files had to be stubbed, so the workflow can upload the
    evidence. Without this the sanitiser HIDES the bug it exists to contain: it
    repairs the file, the reporter succeeds, and an upload conditioned only on
    reporter failure never fires. The 2026-08-10 green run stubbed three files
    and would have discarded all three."""
    path = os.environ.get('GITHUB_OUTPUT')
    if path:
        with open(path, 'a') as fh:
            fh.write(f'stubbed={stubbed}\n')


def main():
    files = sorted(glob.glob(f'{REPORT_DIR}/**/*.xml', recursive=True))
    if not files:
        print(f'sanitise: no XML found under {REPORT_DIR}')
        emit_output(0)
        return 0

    os.makedirs(RAW_DIR, exist_ok=True)
    stubbed = 0

    for path in files:
        with open(path, 'rb') as fh:
            raw = fh.read()

        # Keep the untouched original alongside, flattened so the names stay
        # readable in the artifact listing.
        shutil.copyfile(path, os.path.join(RAW_DIR, os.path.basename(path)))

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
            stubbed += 1

        if final != raw:
            with open(path, 'wb') as fh:
                fh.write(final)
        print(f'sanitise: {path} [{note}]')

    if stubbed:
        # Loud, because a stub silently drops that suite's per-test detail from
        # the report — only its counts survive.
        print(f'sanitise: {stubbed} file(s) were not well formed and were '
              f'reduced to counts. Originals kept in {RAW_DIR}/ and uploaded '
              f'as a build artifact.')
    emit_output(stubbed)
    return 0


if __name__ == '__main__':
    sys.exit(main())
