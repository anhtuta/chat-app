# Severity Levels

- 🔴 Critical: Severe bugs, security vulnerabilities, or data loss risks that could cause system failures.
- 🟠 Major: Significant problems that negatively impact system performance or core functionality.
- 🟡 Minor: Lower-priority code quality issues or edge cases that should ideally be fixed.
- 🔵 Trivial: Minor styling, naming, or low-impact suggestions for clean code.
- ⚪ Info: Contextual or informational comments that do not require an active code change.

These levels are from [CodeRabbit](https://docs.coderabbit.ai/guides/code-review-overview), but I will set level for each issue based on my own judgment.

# Feature docs

- Each feature or issue/bug should have a dedicated doc file in the `docs` folder, start with `N_` prefix.
- Git commit message should start with `F-N` prefix. Examples:
  - "F-123 Phase 1: Implement feature X"
  - "F-123 P2: Implement feature Y"
