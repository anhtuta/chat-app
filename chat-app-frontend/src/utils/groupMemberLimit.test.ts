import {
  formatMaxMembersInput,
  isGroupAtOrOverMemberLimit,
  isUnlimitedMaxMembers,
  maxMembersEquals,
  parseMaxMembersInput,
  remainingMemberSeats,
} from "./groupMemberLimit";

describe("groupMemberLimit", () => {
  it("treats null and 0 as unlimited", () => {
    expect(isUnlimitedMaxMembers(null)).toBe(true);
    expect(isUnlimitedMaxMembers(0)).toBe(true);
    expect(isUnlimitedMaxMembers(undefined)).toBe(true);
    expect(isUnlimitedMaxMembers(5)).toBe(false);
  });

  it("detects at-or-over capacity only for a positive cap", () => {
    expect(isGroupAtOrOverMemberLimit(10, null)).toBe(false);
    expect(isGroupAtOrOverMemberLimit(10, 0)).toBe(false);
    expect(isGroupAtOrOverMemberLimit(9, 10)).toBe(false);
    expect(isGroupAtOrOverMemberLimit(10, 10)).toBe(true);
    expect(isGroupAtOrOverMemberLimit(12, 10)).toBe(true);
  });

  it("computes remaining seats as null when unlimited", () => {
    expect(remainingMemberSeats(3, null)).toBeNull();
    expect(remainingMemberSeats(3, 10)).toBe(7);
    expect(remainingMemberSeats(12, 10)).toBe(0);
  });

  it("parses blank and digits, and rejects negatives or junk", () => {
    expect(parseMaxMembersInput("")).toEqual({ ok: true, value: null });
    expect(parseMaxMembersInput("  ")).toEqual({ ok: true, value: null });
    expect(parseMaxMembersInput("0")).toEqual({ ok: true, value: 0 });
    expect(parseMaxMembersInput("100")).toEqual({ ok: true, value: 100 });
    expect(parseMaxMembersInput("-1")).toEqual({
      ok: false,
      message: "maxMembers must not be negative",
    });
    expect(parseMaxMembersInput("1.5")).toEqual({
      ok: false,
      message: "maxMembers must not be negative",
    });
  });

  it("formats unlimited as an empty field", () => {
    expect(formatMaxMembersInput(null)).toBe("");
    expect(formatMaxMembersInput(0)).toBe("");
    expect(formatMaxMembersInput(8)).toBe("8");
  });

  it("equates stored 0 and null as unlimited", () => {
    expect(maxMembersEquals(null, 0)).toBe(true);
    expect(maxMembersEquals(5, 5)).toBe(true);
    expect(maxMembersEquals(5, null)).toBe(false);
  });
});
