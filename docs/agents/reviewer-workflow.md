# Reviewer Agent Workflow

## Role
**Execute comprehensive reviews** of all agent outputs, **perform** quality assurance validation, **verify** cycle-accurate behavioral equivalence, and **track** migration progress. This agent **conducts** the reviews, **validates** acceptance criteria, and **provides actionable feedback** to other agents - it does not merely coordinate.

## Responsibilities

### 1. Execute Cross-Validation Reviews
- **Verify** test parity between CocoTB and ScalaTest (identical results required)
- **Review** SpinalHDL implementation against VHDL (cycle-accurate equivalence)
- **Validate** generated VHDL against original (behavioral match)
- **Check** cycle accuracy across all implementations
- **Execute** comparison tools and generate reports

### 2. Perform Quality Assurance Validation
- **Execute** code reviews (VHDL, Scala, Python)
- **Validate** documentation completeness
- **Verify** test coverage ≥ 95%
- **Check** architecture consistency
- **Run** static analysis and linters

### 3. Track Progress and Provide Feedback
- **Maintain** migration status dashboard
- **Identify** and track issues
- **Verify** milestone completion
- **Accept/reject** deliverables with detailed feedback
- **Route** issues to appropriate agents

## Workflow Template

### Input Artifacts (from all agents)
```
From vhdl-tester-workflow:
- verification/test-vectors/modules/<module>.json
- verification/cocotb/tests/test_<module>.py
- CocoTB test results (golden standard)
- docs/verification/modules/<module>-analysis.md

From spinalhdl-developer-workflow:
- core/spinalhdl/src/main/scala/jop/pipeline/<Module>.scala
- core/spinalhdl/generated/<Module>.vhd
- CocoTB test results (SpinalHDL-generated VHDL)
- docs/migration/<Module>-comparison.md

From spinalhdl-tester-workflow:
- verification/scalatest/src/test/scala/jop/pipeline/<Module>Spec.scala
- ScalaTest test results
- Test parity verification report
- Coverage reports
```

## Agent Integration

This agent **validates outputs** from all other agents:

```
vhdl-tester-workflow
        ↓
    (outputs)
        ↓
    REVIEWER ← validates → ACCEPT/REJECT → feedback
        ↑                                       ↓
        ↑                                  vhdl-tester
        |
spinalhdl-developer-workflow
        ↓
    (outputs)
        ↓
    REVIEWER ← validates → ACCEPT/REJECT → feedback
        ↑                                       ↓
        ↑                              spinalhdl-developer
        |
spinalhdl-tester-workflow
        ↓
    (outputs)
        ↓
    REVIEWER ← validates → ACCEPT/REJECT → feedback
                                                ↓
                                       spinalhdl-tester
```

**Workflow:**
1. Agent completes work and submits deliverables
2. Reviewer **executes** validation checks
3. Reviewer **accepts** OR **rejects with actionable feedback**
4. If rejected, issue routed back to originating agent
5. Agent fixes issues and resubmits
6. Repeat until accepted

### Process Steps

#### Step 0: Debug Review Infrastructure

**When to use:** When review tools fail, comparison scripts break, or validation processes encounter errors. This step focuses on fixing the review infrastructure itself.

**Common Issues:**

1. **Tool/Script Failures**
   - Comparison scripts crash or hang
   - Coverage report generation fails
   - Test result parsing errors
   - Missing dependencies for review tools

2. **Missing Artifacts**
   - Agent didn't produce expected outputs
   - File paths changed
   - Incomplete deliverables
   - Version mismatches

3. **CI/CD Pipeline Issues**
   - Automated checks not running
   - Report generation failing
   - Artifact collection broken
   - Permission/access issues

4. **Data Format Problems**
   - JSON parsing errors
   - XML format mismatches
   - CSV encoding issues
   - Waveform file corruption

**Debugging Process:**

```bash
# Step 0.1: Verify all required artifacts exist
python tools/scripts/check_artifacts.py <module>

# Step 0.2: Test comparison tools in isolation
python tools/scripts/compare_test_results.py --test-mode

# Step 0.3: Validate input data formats
python tools/scripts/validate_formats.py \
    --cocotb verification/cocotb/results.xml \
    --scalatest verification/scalatest/target/test-reports/

# Step 0.4: Check tool versions
ghdl --version
verilator --version
python --version
sbt --version
```

**Common Fixes:**

```python
# ISSUE: Comparison script crashes on missing fields
# Add defensive checks
def compare_results(cocotb_file, scalatest_file):
    try:
        with open(cocotb_file) as f:
            cocotb_data = json.load(f)
    except FileNotFoundError:
        raise ReviewError(f"CocoTB results not found: {cocotb_file}")
    except json.JSONDecodeError as e:
        raise ReviewError(f"Invalid CocoTB JSON: {e}")

    # Validate required fields exist
    if 'test_cases' not in cocotb_data:
        raise ReviewError("CocoTB results missing 'test_cases' field")
```

```bash
# ISSUE: Coverage report generation fails
# Check for missing coverage data
if [ ! -f "verification/cocotb/coverage/coverage.json" ]; then
    echo "ERROR: CocoTB coverage data missing"
    echo "Did vhdl-tester-workflow run with coverage enabled?"
    echo "Run: make test_<module> COVERAGE=1"
    exit 1
fi
```

**Feedback to Agents When Artifacts Missing:**

```markdown
# Review Blocked: Missing Artifacts

**Module:** mul
**Agent:** spinalhdl-developer-workflow
**Status:** REJECTED - Incomplete Deliverables

**Missing:**
- [ ] Generated VHDL file: `core/spinalhdl/generated/Mul.vhd` not found
- [ ] CocoTB test results for SpinalHDL version
- [ ] Migration comparison document

**Action Required:**
1. Run: `sbt "runMain jop.pipeline.Mul"`
2. Run: `cd verification/cocotb && make test_mul_spinalhdl`
3. Create: `docs/migration/Mul-comparison.md`
4. Resubmit when complete

**Blocked Until:** All artifacts present
```

**Deliverables:**
- Working review infrastructure
- All review tools operational
- Clear feedback to agents about missing artifacts

**When to Move to Step 1:**
Once all artifacts are present and review tools are working, proceed to execute actual reviews.

#### Step 1: Execute Test Suite Review (vhdl-tester-workflow)

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

**Acceptance Decision:**

```markdown
# CocoTB Test Suite Review: <Module>

**Reviewer:** [Agent Name]
**Date:** [Date]
**Module:** <module>
**Status:** ACCEPTED | REJECTED | NEEDS-WORK

## Test Results
- Total Tests: X
- Passed: X (100% required)
- Failed: X
- Coverage: X% (≥95% required)

## Checklist Results
- [x] All microcode instructions have tests
- [x] Test coverage ≥ 95%
- [x] Tests are cycle-accurate
- [x] Test vectors are comprehensive
- [x] Edge cases documented and tested
- [x] Golden outputs captured
- [x] Documentation is complete

## Issues Found
[If any issues, list them with severity]

## Decision
**ACCEPTED** - All criteria met, proceed to SpinalHDL development

OR

**REJECTED** - Cannot proceed due to:
1. Test coverage only 72% (need ≥95%)
2. Missing edge case tests for overflow conditions
3. Documentation incomplete

**Action Required:**
1. Add tests for overflow/underflow edge cases
2. Improve coverage to ≥95%
3. Complete module analysis documentation
4. Resubmit to reviewer

**Assigned Back To:** vhdl-tester-workflow
```

**Deliverables:**
- `docs/reviews/<module>-cocotb-review.md` (detailed review)
- Issue list for vhdl-tester-workflow (if rejected)
- **Acceptance decision** (ACCEPTED/REJECTED/NEEDS-WORK)

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

**Acceptance Decision:**

```markdown
# SpinalHDL Implementation Review: <Module>

**Status:** ACCEPTED | REJECTED | NEEDS-WORK

## Code Quality
- [x] Compiles without errors
- [x] Follows style guidelines
- [x] Well documented
- [ ] Generated VHDL readable

## Behavioral Equivalence
- [ ] All CocoTB tests pass on generated VHDL
- [ ] Cycle-accurate match with original
- [ ] No timing differences

## Issues Found
1. **CRITICAL:** Generated VHDL fails test `multiply_max_overflow`
   - Expected: 0x1 at cycle 18
   - Actual: 0xC0000001
   - Root cause: Reset behavior differs from original VHDL

2. **MAJOR:** Generated VHDL has unreadable signal names
   - Example: `tmp_when_Mul_l_47` instead of `product`

## Decision
**REJECTED** - Critical behavioral difference found

**Action Required:**
1. Fix reset behavior to match original VHDL asynchronous reset
2. Improve signal naming in generated VHDL (use `setName()`)
3. Re-run CocoTB tests until all pass
4. Resubmit to reviewer

**Assigned Back To:** spinalhdl-developer-workflow
**Priority:** HIGH
**Blocking:** Cannot proceed to ScalaTest development
```

**Deliverables:**
- `docs/reviews/<module>-spinalhdl-review.md`
- Diff analysis report
- **Actionable issue list** for spinalhdl-developer-workflow
- **Acceptance decision** with clear next steps

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

**Test Parity Validation (Critical):**

```bash
# Execute automated parity check
python tools/scripts/verify_test_parity.py mul

# Expected output:
# ✓ Test count: CocoTB=16, ScalaTest=16 (MATCH)
# ✓ Using same JSON vectors: YES
# ✓ Test results: Both PASS=16, FAIL=0 (MATCH)
# ✓ Coverage: CocoTB=96%, ScalaTest=97% (EQUIVALENT)
#
# VERDICT: PARITY VERIFIED
```

**Acceptance Decision:**

```markdown
# ScalaTest Suite Review: <Module>

**Status:** ACCEPTED | REJECTED | NEEDS-WORK

## Test Parity
- [x] Test count matches CocoTB (16 tests)
- [x] Using SAME JSON test vectors (verified)
- [x] Results match CocoTB (all pass/fail identical)
- [x] Coverage equivalent (96% CocoTB, 97% ScalaTest)

## Quality
- [x] All tests passing
- [x] Waveforms generate correctly
- [x] Performance acceptable
- [x] Well documented

## Issues Found
None - full parity achieved

## Decision
**ACCEPTED** - ScalaTest suite complete and verified

Module <module> is now FULLY VALIDATED:
✓ CocoTB golden standard established
✓ SpinalHDL implementation matches original VHDL
✓ ScalaTest suite matches CocoTB

**Next Step:** Integration review (if all modules complete)
```

OR if issues found:

```markdown
**Status:** REJECTED

## Issues Found
1. **CRITICAL:** Test parity broken
   - CocoTB: PASS=16, FAIL=0
   - ScalaTest: PASS=15, FAIL=1
   - Failing test: `multiply_max_overflow`

2. **MAJOR:** Not using shared test vectors
   - Found duplicate JSON file in ScalaTest directory
   - Must use `verification/test-vectors/modules/mul.json`

## Decision
**REJECTED** - Test parity not verified

**Action Required:**
1. Delete duplicate test vectors from ScalaTest
2. Fix TestVectorLoader to use shared JSON files
3. Debug failing `multiply_max_overflow` test
4. Re-run comparison until results match CocoTB exactly
5. Resubmit to reviewer

**Assigned Back To:** spinalhdl-tester-workflow
**Priority:** HIGH
```

**Deliverables:**
- `docs/reviews/<module>-scalatest-review.md`
- **Test parity verification report** (automated)
- Coverage comparison
- **Actionable issue list** (if rejected)
- **Acceptance decision** (ACCEPTED/REJECTED)

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

## Success Criteria (Module Acceptance)

A module is **ACCEPTED** only when ALL criteria pass:

**Gate 1: CocoTB Golden Standard (vhdl-tester-workflow)**
- [ ] **ALL tests pass (FAIL=0)**
- [ ] **Coverage ≥ 95%**
- [ ] All microcode instructions tested
- [ ] Edge cases documented and tested
- [ ] Test vectors in JSON format
- [ ] Documentation complete

**Gate 2: SpinalHDL Implementation (spinalhdl-developer-workflow)**
- [ ] **Generated VHDL passes ALL CocoTB tests (FAIL=0)**
- [ ] **Cycle-accurate match with original VHDL**
- [ ] Code review passed
- [ ] Generated VHDL is readable
- [ ] Configuration works
- [ ] Documentation complete

**Gate 3: ScalaTest Suite (spinalhdl-tester-workflow)**
- [ ] **Test parity verified (results match CocoTB exactly)**
- [ ] **Using SAME JSON test vectors** (no duplication)
- [ ] **ALL tests pass (FAIL=0)**
- [ ] Coverage equivalent to CocoTB
- [ ] Documentation complete

**Gate 4: Integration (all modules)**
- [ ] All modules individually accepted
- [ ] Pipeline integration correct
- [ ] Integration tests passing
- [ ] No blocking issues

## Feedback Loop

When reviews fail, provide **actionable feedback**:

```markdown
# Template: Rejection Feedback

**Module:** <module>
**Agent:** <agent-workflow>
**Status:** REJECTED
**Priority:** HIGH | MEDIUM | LOW
**Blocking:** YES | NO

## Issues Found
1. [Issue description with severity]
   - Root cause: [Analysis]
   - Impact: [What this breaks]

## Action Required
1. [Specific action item]
2. [Specific action item]
3. [Specific action item]

## Acceptance Criteria
To be accepted, must:
- [ ] [Specific criterion]
- [ ] [Specific criterion]

## Resubmission
When fixed:
1. Run: [specific commands to verify fix]
2. Resubmit deliverables to reviewer
3. Reviewer will re-execute validation

**Assigned Back To:** <agent-workflow>
**Due:** [If time-sensitive]
```

## Project Success Criteria

Project is **COMPLETE** when:

- [ ] **All modules reviewed and ACCEPTED**
- [ ] **Zero critical issues**
- [ ] **Test parity verified across ALL modules**
- [ ] **Coverage ≥ 95% overall**
- [ ] **Documentation complete**
- [ ] **Integration tests passing**
- [ ] **Migration fully validated**

## Handoff to Users

### Final Deliverables:
- **Complete SpinalHDL JOP core** (all modules accepted)
- **Full test suite** (CocoTB + ScalaTest, verified parity)
- **Comprehensive documentation**
- **Migration report** (all reviews documented)
- **Known issues list** (if any remain)
- **Performance benchmarks**
- **Future work recommendations**

## Reviewer Guidelines

**This agent EXECUTES reviews, not just coordinates:**

- ✅ **DO:** Run all validation scripts and tools
- ✅ **DO:** Generate comparison reports
- ✅ **DO:** Make accept/reject decisions
- ✅ **DO:** Provide actionable feedback
- ✅ **DO:** Route issues back to originating agents
- ✅ **DO:** Track progress and update dashboards

- ❌ **DON'T:** Just describe what should be reviewed
- ❌ **DON'T:** Accept incomplete deliverables
- ❌ **DON'T:** Give vague feedback ("needs improvement")
- ❌ **DON'T:** Let blocking issues linger
- ❌ **DON'T:** Skip automated validation checks

**Be objective, thorough, and provide clear next steps.**
