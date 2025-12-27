# Reviewer Agent Workflow

## Role
Cross-validate work from all agents, ensure quality, verify equivalence, and track migration progress.

## Responsibilities

### 1. Cross-Validation
- Verify test parity between CocoTB and ScalaTest
- Review SpinalHDL implementation against VHDL
- Validate generated VHDL against original
- Check cycle accuracy across all implementations

### 2. Quality Assurance
- Code review (VHDL, Scala, Python)
- Documentation completeness
- Test coverage verification
- Architecture consistency

### 3. Progress Tracking
- Migration status tracking
- Issue identification and tracking
- Milestone verification
- Deliverable acceptance

## Workflow Template

### Input Artifacts
```
All outputs from:
- vhdl-tester
- spinalhdl-developer
- spinalhdl-tester
```

### Process Steps

#### Step 1: Review Test Suite (vhdl-tester)

**Checklist:**
- [ ] All microcode instructions have tests
- [ ] Test coverage ≥ 95%
- [ ] Tests are cycle-accurate
- [ ] Test vectors are comprehensive
- [ ] Edge cases documented and tested
- [ ] Golden outputs captured
- [ ] Documentation is complete

**Review Process:**
```bash
# Clone and setup
cd verification/cocotb

# Run full test suite
make test_all

# Check coverage
make coverage
firefox coverage/index.html

# Verify test quality
python tools/analyze_coverage.py
```

**Deliverables:**
- `docs/reviews/<module>-cocotb-review.md`
- Issue list for vhdl-tester
- Acceptance status (pass/fail/needs-work)

#### Step 2: Review SpinalHDL Implementation (spinalhdl-developer)

**Checklist:**
- [ ] All modules ported from VHDL
- [ ] Signal-level compatibility maintained
- [ ] Generated VHDL is readable
- [ ] Configuration system works
- [ ] Code follows style guidelines
- [ ] Documentation is complete
- [ ] Pipeline integration correct

**Review Process:**

```scala
// Compile and check for warnings
sbt clean compile

// Generate VHDL
sbt "runMain jop.JopCore"

// Review generated VHDL
diff -u original/vhdl/core/<module>.vhd \
        core/spinalhdl/generated/<module>.vhd \
        > docs/reviews/<module>-diff.txt

// Analyze differences
```

**Manual Review:**
- Compare VHDL and SpinalHDL side-by-side
- Verify register initialization
- Check reset behavior
- Validate state machines
- Review timing assumptions

**Code Quality:**
```bash
# Scala formatting check
sbt scalafmtCheck

# Run static analysis (if configured)
sbt scalafix
```

**Deliverables:**
- `docs/reviews/<module>-spinalhdl-review.md`
- Diff analysis
- Issue list for spinalhdl-developer
- Acceptance status

#### Step 3: Verify Generated VHDL (cross-check)

**Checklist:**
- [ ] Generated VHDL compiles with GHDL
- [ ] Passes all CocoTB tests
- [ ] Cycle-accurate match with original
- [ ] No synthesis warnings
- [ ] Resource usage acceptable

**Review Process:**
```bash
# Compile generated VHDL
cd verification/cocotb
ghdl -a ../core/spinalhdl/generated/<Module>.vhd

# Run CocoTB tests on generated VHDL
make test_<module>_spinalhdl

# Compare results with original
diff test-results/original-<module>.json \
     test-results/spinalhdl-<module>.json

# Generate comparison report
python tools/compare_test_results.py \
    --original test-results/original-<module>.json \
    --spinalhdl test-results/spinalhdl-<module>.json \
    --output docs/reviews/<module>-comparison.md
```

**Deliverables:**
- Test comparison report
- Verification status
- List of discrepancies (if any)

#### Step 4: Review ScalaTest Suite (spinalhdl-tester)

**Checklist:**
- [ ] All CocoTB tests ported
- [ ] Test vector parity verified
- [ ] Coverage equivalent
- [ ] All tests passing
- [ ] Performance acceptable
- [ ] Documentation complete

**Review Process:**
```bash
# Run ScalaTest suite
cd verification/scalatest
sbt test

# Generate coverage
sbt clean coverage test coverageReport

# Compare with CocoTB coverage
python tools/compare_coverage.py \
    --cocotb ../cocotb/coverage/coverage.json \
    --scalatest target/coverage/scoverage.xml \
    --output ../../docs/reviews/coverage-comparison.md
```

**Test Parity Verification:**
```bash
# Export CocoTB test results
cd verification/cocotb
python tools/export_test_results.py > cocotb-results.json

# Export ScalaTest results
cd verification/scalatest
sbt "testOnly * -- -h test-results"

# Compare
python tools/verify_test_parity.py \
    --cocotb ../cocotb/cocotb-results.json \
    --scalatest test-results/index.html
```

**Deliverables:**
- `docs/reviews/<module>-scalatest-review.md`
- Coverage comparison
- Test parity report
- Issue list for spinalhdl-tester
- Acceptance status

#### Step 5: Integration Review

**Checklist:**
- [ ] Pipeline stages connect correctly
- [ ] Clock domain crossing handled
- [ ] Reset propagation correct
- [ ] Memory interfaces compatible
- [ ] Top-level configuration works
- [ ] Resource usage reasonable

**Review Process:**
```bash
# Generate complete core
cd core/spinalhdl
sbt "runMain jop.JopCore"

# Analyze generated code
ghdl -a generated/JopCore.vhd
# Check for errors/warnings

# Review resource usage (if synthesis available)
# quartus/vivado synthesis and check reports
```

**Integration Testing:**
- Run integration tests in CocoTB
- Run integration tests in ScalaTest
- Verify against known-good test programs
- Check timing closure (if synthesis available)

**Deliverables:**
- `docs/reviews/integration-review.md`
- Resource usage report
- Timing analysis (if available)
- Integration issues

#### Step 6: Documentation Review

**Checklist:**
- [ ] Architecture documentation complete
- [ ] Migration notes documented
- [ ] API documentation current
- [ ] Build instructions clear
- [ ] Configuration guide complete
- [ ] Known issues documented

**Review Process:**
- Read all documentation
- Verify accuracy
- Check for completeness
- Test build instructions
- Validate examples

**Deliverables:**
- Documentation review notes
- List of documentation improvements needed

#### Step 7: Progress Tracking

```markdown
# Template: docs/reviews/migration-status.md

# JOP Core Migration Status

## Overall Progress: XX%

### Modules Status

| Module | VHDL Tests | SpinalHDL Port | Generated VHDL | ScalaTest | Status |
|--------|------------|----------------|----------------|-----------|--------|
| BytecodeFetch | ✅ Pass | ✅ Complete | ✅ Pass | ✅ Pass | **DONE** |
| MicrocodeFetch | ✅ Pass | ✅ Complete | ⚠️ 2 failures | 🔄 In Progress | **IN PROGRESS** |
| MicrocodeDecode | ✅ Pass | 🔄 In Progress | ⏸️ Waiting | ⏸️ Waiting | **IN PROGRESS** |
| Execute | ⏸️ Waiting | ⏸️ Waiting | ⏸️ Waiting | ⏸️ Waiting | **PENDING** |
| StackBuffer | ✅ Pass | ⏸️ Waiting | ⏸️ Waiting | ⏸️ Waiting | **PENDING** |
| MethodCache | ✅ Pass | ⏸️ Waiting | ⏸️ Waiting | ⏸️ Waiting | **PENDING** |

### Current Sprint Status

**Sprint Goal:** Complete MicrocodeFetch migration

**Completed:**
- [x] BytecodeFetch fully migrated and verified
- [x] MicrocodeFetch SpinalHDL implementation
- [x] MicrocodeFetch test vectors

**In Progress:**
- [ ] Fix 2 test failures in generated VHDL (#42, #43)
- [ ] Complete ScalaTest porting for MicrocodeFetch

**Blocked:**
- MicrocodeDecode waiting for MicrocodeFetch completion

### Issues

| ID | Title | Agent | Priority | Status |
|----|-------|-------|----------|--------|
| #42 | Reset timing mismatch in MicrocodeFetch | spinalhdl-developer | High | Open |
| #43 | Edge case: jump table boundary | vhdl-tester | Medium | Open |

### Milestones

- [x] Milestone 1: CocoTB infrastructure (Week 1)
- [x] Milestone 2: First module migrated (Week 2)
- [ ] Milestone 3: Pipeline stages complete (Week 4)
- [ ] Milestone 4: Integration complete (Week 6)

### Metrics

- **Test Coverage:** 87% overall
- **Code Review:** 65% reviewed
- **Documentation:** 45% complete
- **Bugs Found:** 12 total, 8 fixed, 4 open

### Risk Assessment

**High Risks:**
- None currently

**Medium Risks:**
- Timing closure unknown until synthesis
- Some microcode instructions have complex edge cases

**Mitigated Risks:**
- ✅ CocoTB infrastructure working
- ✅ First module successfully migrated
```

**Update Frequency:** Daily or after each agent delivery

**Deliverables:**
- Migration status dashboard
- Updated regularly

### Output Artifacts

```
docs/reviews/
├── migration-status.md              # Overall progress
├── sprint-current.md                # Current sprint
├── issues/
│   ├── 042-reset-timing.md
│   └── 043-jump-boundary.md
├── modules/
│   ├── fetch-cocotb-review.md
│   ├── fetch-spinalhdl-review.md
│   ├── fetch-scalatest-review.md
│   └── fetch-comparison.md
├── integration-review.md
├── documentation-review.md
└── acceptance/
    ├── bytecode-fetch-ACCEPTED.md
    └── microcode-fetch-PENDING.md

docs/metrics/
├── coverage-trends.csv
├── test-results-history.json
└── performance-benchmarks.csv
```

## Review Criteria

### Module Acceptance Criteria

A module is **ACCEPTED** when:
- [ ] All CocoTB tests pass (100%)
- [ ] SpinalHDL implementation reviewed and approved
- [ ] Generated VHDL passes all CocoTB tests
- [ ] ScalaTest suite complete and passing
- [ ] Test parity verified
- [ ] Coverage ≥ 95%
- [ ] Documentation complete
- [ ] No blocking issues
- [ ] Code review approved

### Quality Gates

**Gate 1: CocoTB Tests**
- Must pass before SpinalHDL development starts

**Gate 2: SpinalHDL Implementation**
- Code review must pass
- Generated VHDL must compile

**Gate 3: Generated VHDL Validation**
- Must pass all CocoTB tests
- Cycle-accurate match required

**Gate 4: ScalaTest Suite**
- Test parity verified
- Coverage equivalent

**Gate 5: Integration**
- All modules accepted
- Integration tests passing

## Tools and Scripts

```python
# tools/scripts/verify_module.py
"""
Automated module verification script
Runs all checks and generates report
"""

def verify_module(module_name):
    results = {
        'cocotb_tests': run_cocotb_tests(module_name),
        'spinalhdl_compile': compile_spinalhdl(module_name),
        'vhdl_generation': generate_vhdl(module_name),
        'vhdl_tests': run_vhdl_tests(module_name),
        'scalatest': run_scalatest(module_name),
        'coverage': check_coverage(module_name),
    }

    generate_report(module_name, results)
    return all(results.values())

# tools/scripts/compare_implementations.py
"""
Compare VHDL and SpinalHDL implementations
"""

# tools/scripts/generate_status_report.py
"""
Generate migration status dashboard
"""
```

## Review Commands

```bash
# Run full verification for a module
python tools/scripts/verify_module.py <module>

# Generate status report
python tools/scripts/generate_status_report.py

# Compare implementations
python tools/scripts/compare_implementations.py \
    --original original/vhdl/core/<module>.vhd \
    --spinalhdl core/spinalhdl/generated/<module>.vhd

# Verify test parity
python tools/scripts/verify_test_parity.py <module>

# Check acceptance criteria
python tools/scripts/check_acceptance.py <module>
```

## Success Criteria

- [ ] All modules reviewed and accepted
- [ ] Zero critical issues
- [ ] Test parity verified across all modules
- [ ] Coverage ≥ 95% overall
- [ ] Documentation complete
- [ ] Integration tests passing
- [ ] Migration complete

## Handoff to Users

### Final Deliverables:
- Complete SpinalHDL JOP core
- Full test suite (CocoTB + ScalaTest)
- Comprehensive documentation
- Migration report
- Known issues list
- Future work recommendations

## Notes

- Be objective and thorough
- Document all findings clearly
- Escalate blocking issues immediately
- Maintain traceability
- Update status frequently
- Celebrate milestones!
