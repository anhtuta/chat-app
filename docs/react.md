# Implement theme switcher in React

I’ll wire a multi-palette system with persistence, then add a simple selector in the chat UI so users can switch themes from settings-like controls.

Users can choose from multiple palettes at runtime, and the choice is saved in localStorage and restored on reload.

What was added:

- Theme preset system with 3 palettes (Forest, Amethyst, Ocean) in tokens.js
- Runtime CSS variable application and dynamic MUI theme generation in App.js
- Theme selector dropdown in the sidebar UI in Sidebar.js
- Prop wiring for selector state from app to chat page in ChatPage.js

How it works now:

1. App loads selected theme ID from localStorage.
2. It resolves that palette into:
   - MUI palette tokens for createTheme
   - Full CSS variable map for your existing CSS files
3. It applies CSS variables to :root at runtime.
4. User can switch theme from the sidebar Theme dropdown.

# Implement animation for group reordering in sidebar

I added a FLIP-style animation so groups animate smoothly when reordered.

What I changed

- Sidebar.js
  - Added refs for each `ListItemButton`.
  - Added a `useLayoutEffect` that measures previous and current DOM positions and applies a translateY transform + transition to animate each item into its new place (FLIP technique).
  - Replaced the wrapping `Badge` usage earlier with the CSS-based badge (as we discussed).

- No changes to your CSS were required for the animation itself; layout stays controlled by Sidebar.css.

How it works (short)

- On each render the code records the bounding rects of items.
- When order changes, it computes the vertical offset (previousTop - newTop), applies an immediate inverse translate so the item appears at its old spot, then animates transform back to 0 — producing a smooth move-to-position animation.

Tuning

- Animation speed/easing lives in the JS transition: currently `transform 300ms cubic-bezier(.2,.8,.2,1)`. I can change this to a different duration or easing, or pull it into CSS if you prefer.

Notes / caveats

- The FLIP code animates items that exist in both previous and current lists. New items (inserted) and removed items will appear/disappear without a stagger; we can add enter/exit animations if you want.
- This approach uses inline styles temporarily; cleanup is performed on transition end.
- Please test in your app (dev server). Large lists with many simultaneous moves may make many transitions start at once — we can throttle or stagger if needed.

Want me to:

- Change duration/easing?
- Add an enter/exit fade for newly created/removed groups?
- Cap badge counts like `99+` (UX suggestion)?

Ref: GPT-5 mini

# Fix unnecessary `group-updates` topic unsubscribe/subscribe on chat switch

I’ve identified a likely lifecycle issue (effect cleanup firing on route transitions/remount-like behavior), so I’m implementing a robust fix in `WebSocketProvider`: reference-counted personal subscriptions with delayed cleanup cancellation. This will prevent unnecessary UNSUBSCRIBE/SUBSCRIBE churn even if cleanup runs briefly during chat switches.

Implemented a deeper fix that should stop the unnecessary `group-updates` unsubscribe/resubscribe even if React effect cleanup runs during chat switches.

What I found

- Your logs prove the personal unsubscribe is being triggered by the cleanup function returned from `subscribePersonal` (`"2. unsubscribe called..."`).
- That means a cleanup/re-subscribe lifecycle is happening on chat switch (effect rerun or remount-like transition).
- Previous fixes were too brittle because they still unsubscribed immediately on cleanup.

What I changed

- Updated WebSocketProvider.js with robust personal subscription lifecycle handling:
  - Added reference counting per personal topic (`refCount`).
  - Added delayed cleanup (`cleanupTimer`) before actual unsubscribe.
  - If the same topic is re-subscribed quickly (common during route/effect churn), cleanup timer is canceled and existing subscription is reused.
  - This prevents STOMP UNSUBSCRIBE/SUBSCRIBE flapping for `/topic/user.<username>.group-updates`.
- Also improved context value memo dependencies to include `subscribe`, `unsubscribe`, and `subscribePersonal` references.
- Verified the edited file has no syntax errors using diagnostics.

Why this should work now

- On chat switch, even if cleanup runs transiently:
  - `refCount` drops to `0`, but unsubscribe is delayed.
  - The immediate re-subscribe for the same topic increments `refCount` and cancels pending cleanup.
  - Result: no wire-level UNSUBSCRIBE/SUBSCRIBE for personal topic.

## My manual testing

What to test

1. Reload frontend.
2. Switch group A -> group B several times.
3. Check network STOMP frames:
   - Expected: chat topic still unsub/sub (`/topic/group.x`) on switch.
   - Expected: personal topic `/topic/user.vegeta.group-updates` should no longer flap on each switch.

When user switches groups, websocket messages before:

```
["UNSUBSCRIBE\nid:sub-3\n\n\u0000"]
["UNSUBSCRIBE\nid:sub-2\n\n\u0000"]
["SUBSCRIBE\nid:sub-4\ndestination:/topic/user.vegeta.group-updates\n\n\u0000"]
["SUBSCRIBE\nid:sub-5\ndestination:/topic/group.100\n\n\u0000"]
```

- We unsubscribe from `/topic/group.1` and subscribe to `/topic/group.100` as expected.
- We also see an unnecessary unsubscribe from `/topic/user.vegeta.group-updates` followed by a resubscribe, which is what we want to eliminate.

When user switches groups, websocket messages after:

```
["UNSUBSCRIBE\nid:sub-5\n\n\u0000"]
["SUBSCRIBE\nid:sub-6\ndestination:/topic/group.10\n\n\u0000"]
```

- We still see the expected unsubscribe/subscribe for the group topic.
- We no longer see any unsubscribe/subscribe for the personal topic, which means the fix is working as intended.
