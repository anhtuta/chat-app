import { buildJoinLinkUrl, getSafeInternalPath } from "./joinLinks";

describe("joinLinks", () => {
  describe("getSafeInternalPath", () => {
    it("accepts internal absolute paths", () => {
      expect(getSafeInternalPath("/join/abc")).toBe("/join/abc");
      expect(getSafeInternalPath("/group/public")).toBe("/group/public");
    });

    it("rejects empty, protocol-relative, and absolute URLs", () => {
      expect(getSafeInternalPath(null)).toBeNull();
      expect(getSafeInternalPath("")).toBeNull();
      expect(getSafeInternalPath("//evil.example")).toBeNull();
      expect(getSafeInternalPath("https://evil.example")).toBeNull();
      expect(getSafeInternalPath("group/public")).toBeNull();
    });

    it("rejects backslash and control-character open-redirect bypasses", () => {
      expect(getSafeInternalPath("/\\evil.example")).toBeNull();
      expect(getSafeInternalPath("/\\\tevil.example")).toBeNull();
      expect(getSafeInternalPath("/\t/evil.example")).toBeNull();
      expect(getSafeInternalPath("/\n/evil.example")).toBeNull();
    });

    it("normalizes harmless backslashes inside an otherwise safe path", () => {
      expect(getSafeInternalPath("/join\\abc")).toBe("/join/abc");
    });
  });

  describe("buildJoinLinkUrl", () => {
    it("builds an origin-scoped join URL", () => {
      expect(buildJoinLinkUrl("tok%en", "http://localhost:3000")).toBe(
        "http://localhost:3000/join/tok%25en",
      );
    });
  });
});
