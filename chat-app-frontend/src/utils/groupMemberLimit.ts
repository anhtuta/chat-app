/**
 * Client helpers for optional group member caps.
 * Backend remains authoritative; these only drive UI hints.
 */

export const GROUP_MEMBER_LIMIT_REACHED_MESSAGE = "Group member limit has been reached";

export function isUnlimitedMaxMembers(maxMembers: number | null | undefined): boolean {
  return maxMembers == null || maxMembers <= 0;
}

/**
 * True when a positive cap is known and current membership is already at or above it.
 */
export function isGroupAtOrOverMemberLimit(
  memberCount: number,
  maxMembers: number | null | undefined,
): boolean {
  if (isUnlimitedMaxMembers(maxMembers)) {
    return false;
  }
  return memberCount >= maxMembers;
}

/**
 * Remaining seats under a positive cap, or {@code null} when unlimited.
 */
export function remainingMemberSeats(
  memberCount: number,
  maxMembers: number | null | undefined,
): number | null {
  if (isUnlimitedMaxMembers(maxMembers)) {
    return null;
  }
  return Math.max(0, (maxMembers as number) - memberCount);
}

/**
 * Empty input means unlimited ({@code null}). {@code 0} is also unlimited.
 */
export type ParsedMaxMembers =
  | { ok: true; value: number | null }
  | { ok: false; message: string };

export function parseMaxMembersInput(raw: string): ParsedMaxMembers {
  const trimmed = raw.trim();
  if (trimmed === "") {
    return { ok: true, value: null };
  }
  if (!/^\d+$/.test(trimmed)) {
    return { ok: false, message: "maxMembers must not be negative" };
  }
  const value = Number(trimmed);
  if (!Number.isSafeInteger(value)) {
    return { ok: false, message: "maxMembers must not be negative" };
  }
  return { ok: true, value };
}

export function formatMaxMembersInput(maxMembers: number | null | undefined): string {
  if (isUnlimitedMaxMembers(maxMembers)) {
    return "";
  }
  return String(maxMembers);
}

/** Treats stored {@code null} and {@code 0} as the same unlimited value. */
export function maxMembersEquals(
  left: number | null | undefined,
  right: number | null | undefined,
): boolean {
  const normalize = (value: number | null | undefined): number | null =>
    isUnlimitedMaxMembers(value) ? null : (value as number);
  return normalize(left) === normalize(right);
}
