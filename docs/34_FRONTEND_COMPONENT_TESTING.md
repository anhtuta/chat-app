## Intro

The frontend test suite has two main layers today:

- **Util tests** under `src/utils/` — pure logic, easy to maintain, already well covered.
- **Component tests** under `src/components/` — add these when a component owns user-visible behavior (keyboard, focus, modal lifecycle, validation), not when it is mostly layout.

This doc defines when to add component tests and how to run them. Individual components can be covered incrementally.

## What to test

| Layer                        | Location                       | When                                                                                                       |
| ---------------------------- | ------------------------------ | ---------------------------------------------------------------------------------------------------------- |
| **Utils**                    | `src/utils/*.test.ts`          | Always, for pure functions and business rules                                                              |
| **Components with behavior** | `src/components/**/*.test.tsx` | Keyboard handling, focus trap, open/close lifecycle, form validation, conditional UI driven by props/state |
| **App smoke test**           | `src/App.test.js`              | One happy-path render for routing/auth shell                                                               |
| **E2E**                      | `e2e/` (Playwright)            | Full login → chat flows; see [09_E2E_PLAYWRIGHT.md](09_E2E_PLAYWRIGHT.md)                                  |

### Prefer util tests when logic can live in a util

Extract non-UI logic (permissions, formatting, URL safety, pagination math) into `src/utils/` and test it there. Component tests should focus on wiring and DOM behavior, not re-testing the same rules.

### Add component tests when wrong behavior is user-visible

Good candidates in this codebase:

- Modals and dialogs (`Dialog`, portals, `aria-modal`)
- Keyboard shortcuts (Escape, arrows, Enter to submit)
- Focus management (trap tab, restore focus on close)
- Forms with validation or disabled/submit states
- Components that transform user input before calling callbacks

### Skip or defer low-value component tests

Usually not worth unit-testing in isolation:

- Pure presentational markup with no branching
- Components that only pass props through to MUI with no custom behavior
- Visual styling and layout (unless tied to functional CSS classes)

Use E2E or manual QA for full visual polish instead.

## Conventions

- **Colocate tests:** `ComponentName.test.tsx` next to `ComponentName.tsx`.
- **Test behavior, not implementation:** assert roles, labels, callbacks, focus, and visible text — not internal state or class names unless necessary.
- **Keep tests stable:** prefer `getByRole` / `getByLabelText`; avoid brittle selectors.
- **MUI components:** wrap renders in a test `ThemeProvider` with `disableRipple: true` to avoid `act(...)` noise from `TouchRipple`. Use `fireEvent` for simple clicks; use `userEvent` when simulating real keyboard navigation (Tab).
- **Portals:** React Testing Library queries the whole document, so portaled modals are testable with `screen.getByRole('dialog')`.

## Reference implementation

[`ImagePreview.test.tsx`](../chat-app-frontend/src/components/chat-area/ImagePreview.test.tsx) is the current pattern for a behavior-heavy component:

- open/close rendering
- body scroll lock
- focus on open and restore on close
- tab trap and focus guard
- Escape / backdrop / button close
- arrow keys and navigation buttons

Use it as a template for similar modal or gallery components.

## How to run tests

From `chat-app-frontend/`:

```bash
make test              # all tests once
make test.utils        # src/utils/** only
make test.components   # src/components/** only
make test.app          # App smoke test only
make test.watch        # interactive watch mode (press a for all)
make test.file FILE=ImagePreview
```

Equivalent npm command for a one-shot full run:

```bash
CI=true npm test -- --watchAll=false
```

Note: bare `npm run test` starts watch mode and may only run tests related to recently changed files. Prefer `make test` before commits.

## Backlog (add tests incrementally)

Track component coverage here. Check off items as tests land.

| Component               | Why test                                      | Status |
| ----------------------- | --------------------------------------------- | ------ |
| `ImagePreview`          | Portal modal, focus trap, keyboard nav        | Done   |
| `CreateGroupModal`      | Form validation, submit/cancel                | TODO   |
| `GroupDetailsDialog`    | Tabbed dialog, async member data              | TODO   |
| `AddGroupMemberDialog`  | Search/select, submit disabled states         | TODO   |
| `ChatMessageItem`       | Delete confirm dialog, moderation actions     | TODO   |
| `GroupMemberListItem`   | Role/kick/ban confirm dialogs                 | TODO   |
| `LeaveGroupSection`     | Leave confirm flow                            | TODO   |
| `GroupJoinLinksSection` | Revoke confirm dialog                         | TODO   |
| `ChatMessageComposer`   | Enter to send, attachment UX (if non-trivial) | TODO   |

Utils and App smoke tests are out of scope for this backlog — keep adding util tests whenever new pure logic is introduced.

## Related docs

- [09_E2E_PLAYWRIGHT.md](09_E2E_PLAYWRIGHT.md) — browser-level coverage
- [14_FRONTEND_TYPESCRIPT_MIGRATION.md](14_FRONTEND_TYPESCRIPT_MIGRATION.md) — frontend structure
