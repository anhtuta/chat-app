import {
  canPreviewManageTarget,
  formatGroupRoleLabel,
  getRoleRank,
  isSameOrHigherRole,
  normalizeGroupRole,
  resolveManagePermissionPreview,
} from "./groupRoles";

describe("groupRoles", () => {
  describe("getRoleRank", () => {
    it("returns known role ranks", () => {
      expect(getRoleRank("LEADER")).toBe(1);
      expect(getRoleRank("CO_LEADER")).toBe(2);
      expect(getRoleRank("ELDER")).toBe(3);
      expect(getRoleRank("MEMBER")).toBe(4);
    });

    it("defaults unknown or missing roles to MEMBER rank", () => {
      expect(getRoleRank(null)).toBe(4);
      expect(getRoleRank(undefined)).toBe(4);
      expect(getRoleRank("")).toBe(4);
      expect(getRoleRank("ADMIN")).toBe(4);
    });
  });

  describe("isSameOrHigherRole", () => {
    it("treats lower rank numbers as higher privilege", () => {
      expect(isSameOrHigherRole("LEADER", "MEMBER")).toBe(true);
      expect(isSameOrHigherRole("CO_LEADER", "CO_LEADER")).toBe(true);
      expect(isSameOrHigherRole("ELDER", "MEMBER")).toBe(true);
      expect(isSameOrHigherRole("MEMBER", "ELDER")).toBe(false);
      expect(isSameOrHigherRole("ELDER", "LEADER")).toBe(false);
    });
  });

  describe("normalizeGroupRole", () => {
    it("keeps valid roles and defaults others to MEMBER", () => {
      expect(normalizeGroupRole("LEADER")).toBe("LEADER");
      expect(normalizeGroupRole("CO_LEADER")).toBe("CO_LEADER");
      expect(normalizeGroupRole("ELDER")).toBe("ELDER");
      expect(normalizeGroupRole("MEMBER")).toBe("MEMBER");
      expect(normalizeGroupRole(null)).toBe("MEMBER");
      expect(normalizeGroupRole("ADMIN")).toBe("MEMBER");
    });
  });

  describe("formatGroupRoleLabel", () => {
    it("formats underscores as spaces", () => {
      expect(formatGroupRoleLabel("CO_LEADER")).toBe("CO LEADER");
      expect(formatGroupRoleLabel("LEADER")).toBe("LEADER");
      expect(formatGroupRoleLabel(undefined)).toBe("MEMBER");
    });
  });

  describe("resolveManagePermissionPreview", () => {
    it("prefers the strongest available management permission", () => {
      expect(resolveManagePermissionPreview(["MANAGE_ROLES", "BAN_MEMBERS", "KICK_MEMBERS"]))
        .toBe("MANAGE_ROLES");
      expect(resolveManagePermissionPreview(["BAN_MEMBERS", "KICK_MEMBERS"])).toBe("BAN_MEMBERS");
      expect(resolveManagePermissionPreview(["KICK_MEMBERS"])).toBe("KICK_MEMBERS");
    });

    it("falls back to KICK_MEMBERS when no management permission is present", () => {
      expect(resolveManagePermissionPreview([])).toBe("KICK_MEMBERS");
      expect(resolveManagePermissionPreview(["SEND_MESSAGES"])).toBe("KICK_MEMBERS");
    });
  });

  describe("canPreviewManageTarget", () => {
    const baseArgs = {
      actorUsername: "alice",
      actorRole: "CO_LEADER",
      actorPermissions: ["KICK_MEMBERS"],
      targetUsername: "bob",
      targetRole: "MEMBER",
      requiredPermission: "KICK_MEMBERS",
    };

    it("returns true when actor has permission and same-or-higher rank over a non-leader", () => {
      expect(canPreviewManageTarget(baseArgs)).toBe(true);
    });

    it("returns false without the required permission", () => {
      expect(
        canPreviewManageTarget({
          ...baseArgs,
          actorPermissions: ["ADD_MEMBERS"],
        }),
      ).toBe(false);
    });

    it("returns false when acting on self", () => {
      expect(
        canPreviewManageTarget({
          ...baseArgs,
          targetUsername: "alice",
        }),
      ).toBe(false);
    });

    it("returns false when the target is the leader", () => {
      expect(
        canPreviewManageTarget({
          ...baseArgs,
          targetRole: "LEADER",
        }),
      ).toBe(false);
    });

    it("returns false when the target has a higher role", () => {
      expect(
        canPreviewManageTarget({
          ...baseArgs,
          actorRole: "ELDER",
          targetRole: "CO_LEADER",
        }),
      ).toBe(false);
    });
  });
});
