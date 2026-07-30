import { formatAbsoluteTimeVi, formatRelativeTime } from "./dateUtils";

describe("dateUtils", () => {
  describe("formatRelativeTime", () => {
    beforeEach(() => {
      jest.useFakeTimers();
      jest.setSystemTime(new Date("2026-07-30T12:00:00.000Z"));
    });

    afterEach(() => {
      jest.useRealTimers();
    });

    it("returns just now for missing, invalid, future, or recent timestamps", () => {
      expect(formatRelativeTime(null)).toBe("just now");
      expect(formatRelativeTime(undefined)).toBe("just now");
      expect(formatRelativeTime("not-a-date")).toBe("just now");
      expect(formatRelativeTime("2026-07-30T11:59:30.000Z")).toBe("just now");
      expect(formatRelativeTime("2026-07-30T13:00:00.000Z")).toBe("just now");
    });

    it("formats minute, hour, day, month, and year buckets", () => {
      expect(formatRelativeTime("2026-07-30T11:55:00.000Z")).toBe("5min ago");
      expect(formatRelativeTime("2026-07-30T09:00:00.000Z")).toBe("3h ago");
      expect(formatRelativeTime("2026-07-28T12:00:00.000Z")).toBe("2d ago");
      expect(formatRelativeTime("2026-05-30T12:00:00.000Z")).toBe("2mo ago");
      expect(formatRelativeTime("2024-07-30T12:00:00.000Z")).toBe("2y ago");
    });
  });

  describe("formatAbsoluteTimeVi", () => {
    it("returns empty string for missing or invalid timestamps", () => {
      expect(formatAbsoluteTimeVi(null)).toBe("");
      expect(formatAbsoluteTimeVi(undefined)).toBe("");
      expect(formatAbsoluteTimeVi("")).toBe("");
      expect(formatAbsoluteTimeVi("not-a-date")).toBe("");
    });

    it("formats a valid timestamp with en-GB short date and time", () => {
      const formatted = formatAbsoluteTimeVi("2026-04-29T14:30:00.000Z");

      // Locale output can vary slightly by environment (comma/space), but date and time parts stay stable.
      expect(formatted).toMatch(/29\/04\/2026/);
      expect(formatted).toMatch(/\d{1,2}:\d{2}/);
    });
  });
});
