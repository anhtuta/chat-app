## Current Problem

The frontend repeatedly hits styling bugs where custom CSS appears correct in the codebase but does not actually affect Material UI components at runtime.

This shows up most often in surfaces such as:

- `Dialog`
- `TextField`
- `Menu`
- `Popover`
- `Chip`
- other MUI components with internal slots or state classes

Common symptoms:

- dark-theme text fields render with low contrast or unreadable text
- wrapper-scoped CSS selectors do not affect dialog or menu content
- input label, outline, placeholder, and disabled styles drift apart
- local fixes work once, but similar issues reappear in later features

Recent concrete examples from this codebase:

- `GroupDetailsDialog` styling initially targeted descendants under a wrapper class, but the real `Dialog` content was rendered in a portal under `document.body`, so those selectors never matched
- `TextField` text color for group name, description, and member search stayed dark on dark themes even after CSS was added, because MUI internal input styles and browser text-fill behavior overrode plain `color`
- different parts of the same MUI control needed separate targeting:
  - root input container
  - actual input / textarea
  - label
  - helper text
  - outline / fieldset
  - focused / disabled states

The problem is not only visual inconsistency. It also increases implementation cost, review churn, and debugging time because each feature risks re-learning the same MUI-specific styling lessons.

## Possible Solutions

### 1. Keep current ad hoc CSS-first approach

- How it works: continue styling MUI components mostly through plain CSS files and fix issues case by case when they appear.
- Pros:
  - lowest short-term process change
  - familiar to the team
  - no migration work
- Cons:
  - keeps repeating the same failure mode
  - encourages wrapper-descendant selectors that often fail with portals and slots
  - makes dark theme issues harder to predict
  - styling logic becomes fragmented across CSS, class names, and emergency overrides
- Recommendation for our problem: No

### 2. Standardize Material UI styling with a clear layering strategy

- How it works:
  - keep MUI Material as the component library
  - keep app design tokens in CSS variables
  - style MUI internals primarily through:
    - `sx` for one-off component-local overrides
    - shared `sx` objects / helpers for repeated patterns
    - theme `components` overrides for app-wide defaults
    - `slotProps` / paper / input props for portal-rendered or slot-heavy components
  - use plain CSS mostly for layout, wrappers, spacing, and non-MUI DOM
- Pros:
  - fixes the root cause without a library migration
  - works with the current codebase and current theme tokens
  - reduces future regressions by making styling decisions predictable
  - keeps implementation incremental and reviewable
- Cons:
  - requires team discipline and conventions
  - some existing components may need cleanup to align with the new pattern
  - developers must learn which MUI layers are safe to style with plain CSS vs `sx` vs theme overrides
- Recommendation for our problem: Yes

### 3. Move from MUI Material to Joy UI

- How it works: gradually replace Material components with Joy UI components and align theming/styling around Joy's CSS-variable-first model.
- Pros:
  - Joy UI can feel more token-centric and modern for design-system work
  - some teams find its styling model more ergonomic for custom themes
- Cons:
  - this is still an MUI-family stack with slots, variants, and portal behavior, so the same category of issues can still happen
  - migration cost is large relative to the actual problem
  - mixed Material + Joy usage can add more complexity before things get simpler
  - would shift effort away from product work into UI-platform migration
- Recommendation for our problem: No
- When I'd use it (only if NOT recommended): only if we already want Joy UI for broader product/design-system reasons, not just to fix CSS override pain

### 4. Move away from MUI entirely to a lower-level styling stack

- How it works: replace MUI components over time with another component system or more custom primitives.
- Pros:
  - full control over markup and styling behavior
  - fewer surprises from library-specific slots and injected styles
- Cons:
  - largest migration and regression surface
  - high product velocity cost
  - would require rebuilding many mature controls ourselves
- Recommendation for our problem: No

## High level Architecture/Design

### Component Diagram / Flowchart / Sequence Diagram

```text
Design tokens (CSS variables in `:root`)
        |
        v
MUI theme (`createTheme`, component overrides)
        |
        +--> shared MUI style helpers (`sx`, slotProps helpers)
        |          |
        |          v
        |    MUI components with slots / portal surfaces
        |
        +--> plain CSS classes for layout / wrappers / non-MUI DOM

Goal:
- tokens define colors
- theme defines global defaults
- shared helpers define repeated MUI internals
- local `sx` handles feature-specific exceptions
- plain CSS handles layout, not fragile deep MUI internals
```

### Key Root Causes

1. **Portal rendering**

- Components like `Dialog`, `Menu`, and `Popover` render outside the visual wrapper component tree.
- CSS such as `.feature-wrapper .MuiInputBase-input` can fail because the real DOM is no longer inside `.feature-wrapper`.

2. **Slot-based component structure**

- Many MUI controls are composites.
- Styling the root does not automatically style:
  - label
  - input text
  - placeholder
  - helper text
  - outline
  - selected / focused / disabled states

3. **Injected style precedence**

- MUI styles are generated and injected with their own order and specificity.
- Plain CSS can lose if it targets the wrong node, loads earlier, or is less specific.

4. **Browser-specific input rendering**

- Inputs and textareas may require `-webkit-text-fill-color` in addition to `color`, especially in WebKit-based browsers.

5. **No single styling contract for MUI in the repo**

- Different features currently mix:
  - plain CSS
  - MUI defaults
  - one-off inline overrides
  - custom class names
- Without a clear hierarchy, the same styling bug reappears in new surfaces.

### Use Cases

- Style a dark-theme `TextField` and guarantee readable input text, label, helper text, and outline.
- Style a `Dialog` or `Menu` that renders in a portal without relying on fragile ancestor selectors.
- Reuse the same MUI field styling in multiple features without copy-pasting CSS.
- Add new themed UI sections without re-debugging MUI precedence from scratch.

## Recommendation

Recommended path:

1. Keep **MUI Material** as the component library.
2. Keep the existing **CSS variable token system** as the source of truth for colors and surfaces.
3. Define a repo-wide MUI styling contract:
   - plain CSS for layout/wrappers/non-MUI DOM
   - shared `sx` helpers for repeated MUI control styling
   - `slotProps` / paper props / input props for portal and slot-specific styling
   - theme-level `components` overrides for app-wide defaults
4. Treat portal-based MUI components as a separate category and always style them via their actual rendered slot (`paper`, `listbox`, etc.), not wrapper descendants.
5. Add a small frontend convention doc or rule later so future features follow the same styling approach.

### Recorded Decision

- We should solve this as a **styling architecture / convention problem**, not a component-library migration problem.
- Joy UI is not the recommended fix for the current pain point.
- The first optimization should standardize how this repo styles MUI before considering any larger UI-library migration.

## Implementation details

## Future Higher-Scale Path

- Add app-wide MUI `components` theme overrides for the most common primitives:
  - `MuiTextField`
  - `MuiOutlinedInput`
  - `MuiInputLabel`
  - `MuiDialog`
  - `MuiMenu`
  - `MuiChip`
- Add a lightweight repo rule or frontend style guide documenting:
  - when to use plain CSS
  - when to use `sx`
  - when to use `slotProps`
  - how to handle portal components
- If the design system grows significantly, evaluate whether a more formal component wrapper layer is needed around raw MUI components.
- Re-evaluate Joy UI or another stack only if broader product/design-system goals justify a dedicated migration.
