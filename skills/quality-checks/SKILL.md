---
name: quality-checks
description: Run code quality checks including formatting (Spotless/google-java-format), static analysis (Error Prone, NullAway, Checkstyle), and tests (JUnit). Use this skill when the user asks to check code quality, run linting, verify formatting, run all checks, or before committing/shipping code.
---

# Quality Checks

Run the full suite of code quality checks for this Java project. Execute each step sequentially and report results.

## Steps

### 1. Formatting Check (Spotless + google-java-format)

Run:
```bash
mvn spotless:check
```

If formatting violations are found, report them and offer to auto-fix with `mvn spotless:apply`.

### 2. Static Analysis (Error Prone + NullAway + Checkstyle)

Run compilation with Error Prone and NullAway:
```bash
mvn compile
```

Then run Checkstyle:
```bash
mvn checkstyle:check
```

Report any warnings or errors from both steps.

### 3. Tests (JUnit)

Check if test sources exist:
```bash
find src/test -name "*.java" -type f 2>/dev/null | head -1
```

If tests exist, run them:
```bash
mvn test
```

If no test files exist, report: "No tests found — skipping. All checks pass by default for tests."

## Reporting

After all steps, provide a summary:

```
Quality Check Results
─────────────────────
Formatting:      ✓ PASS / ✗ FAIL (N violations)
Static Analysis: ✓ PASS / ✗ FAIL (N errors, M warnings)
Checkstyle:      ✓ PASS / ✗ FAIL (N violations)
Tests:           ✓ PASS / ✗ FAIL (N/M passed) / ⊘ SKIPPED (no tests)
```

If any step fails, list the specific issues and suggest fixes.
