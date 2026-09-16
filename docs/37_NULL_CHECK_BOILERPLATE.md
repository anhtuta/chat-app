# Null-Check Boilerplate (`Objects.requireNonNull`)

## Current Problem

Service and publisher methods often start with several `Objects.requireNonNull(...)` lines to:

- fail fast with a named message
- bind a local the compiler/tools treat as non-null
- catch nested nulls (`group.getId()`, mapper returns) before after-commit side effects

`requireNonNull` still throws `NullPointerException`. Direct use (`group.getId()`) also NPEs if `group` is null. The extra lines mainly label the failure, fail at the method door (not inside `AfterCommit`), and distinguish “argument is null” from “nested id / mapper result is null”.

That is useful, but the pattern is repetitive across publishers and services.

## Examples (status quo — before the fix)

```java
public void publishGroupProfileChange(Group group, Message systemMessage, String latestPreview) {
    Group safeGroup = Objects.requireNonNull(group, "group must not be null");
    Message safeMessage = Objects.requireNonNull(systemMessage, "systemMessage must not be null");
    String safePreview = Objects.requireNonNull(latestPreview, "latestPreview must not be null");
    Long groupId = Objects.requireNonNull(safeGroup.getId(), "group.id must not be null");
    // ...
}
```

The same shape appears in `GroupMembershipRealtimePublisher` and many other service methods.

## Possible Solutions

### 1. Lombok `@NonNull` on method parameters

- How it works: Lombok inserts `Objects.requireNonNull` for parameters annotated with `lombok.NonNull`. Nested fields and mapper returns stay explicit.
- Pros: already on the classpath; drops `safeX` locals for parameters; same runtime NPE.
- Cons: only parameters; must use Lombok’s annotation (Spring `@NonNull` is documentation unless configured); nested `getId()` still needs a check.
- Recommendation for our problem: Yes (first incremental step)

### 2. Small util (`nn(value, "name")`)

- How it works: wrap `requireNonNull` with a shorter call and a standard message suffix.
- Pros: works for parameters and nested fields; one convention.
- Cons: still one line per value; extra API to learn; does not remove checks.
- Recommendation for our problem: Optional companion for nested ids / mapper returns

### 3. Annotations only (`org.springframework.lang.NonNull`, JSpecify) without codegen

- How it works: document nullness for IDEs / static analysis. No bytecode unless NullAway / Error Prone / Checker Framework is added.
- Pros: no runtime noise if callers are trusted.
- Cons: does not fail fast with a named message; after-commit NPEs stay harder to debug.
- Recommendation for our problem: No as a standalone replacement
- When I’d use it: later, project-wide with NullAway/JSpecify so runtime checks shrink to ids and mapper returns.

### 4. Drop parameter checks on internal APIs

- How it works: treat caller-proven non-null as a contract; keep checks only for entity ids and nullable mapper results.
- Pros: least code.
- Cons: worse stacks when a caller is wrong; weaker tests that assert messages.
- Recommendation for our problem: No for publishers that schedule `AfterCommit` work

## Recommendation

Do not implement in this pass. When we do:

1. Adopt Lombok `@NonNull` on internal service/publisher parameters that today use `safeX = Objects.requireNonNull(x, "...")`.
2. Keep explicit `requireNonNull` (or a tiny util) for nested ids and mapper returns that annotations cannot express.
3. Keep domain checks (e.g. `MessageType.SYSTEM`) as they are.
4. Apply the same convention in `GroupProfileRealtimePublisher` and `GroupMembershipRealtimePublisher` first, then other hotspots if it pays off.
5. Consider JSpecify + NullAway only if we want to drop runtime parameter checks repo-wide.

## Implementation details

(Not started.)

## Future Higher-Scale Path

If the backend grows a strict nullness toolchain, parameter `@NonNull` plus analysis can replace most runtime argument guards. Keep runtime checks for values static analysis cannot prove: JPA ids before persist, optional associations, and mapper/factory methods annotated nullable.
