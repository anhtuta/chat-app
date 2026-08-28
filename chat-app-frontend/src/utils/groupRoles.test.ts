import { GROUP_ROLES } from "../constant/groupRoles";
import {
  canPreviewManageTarget,
  formatGroupRoleLabel,
  getAssignableGroupRoles,
  getRoleRank,
  isSameOrHigherRole,
  normalizeGroupRole,
  resolveManagePermissionPreview,
} from "./groupRoles";

describe("groupRoles", () => {
  describe("getRoleRank", () => {
    it("returns known role ranks", () => {
      expect(getRoleRank(GROUP_ROLES.LEADER)).toBe(1);
      expect(getRoleRank(GROUP_ROLES.CO_LEADER)).toBe(2);
      expect(getRoleRank(GROUP_ROLES.ELDER)).toBe(3);
      expect(getRoleRank(GROUP_ROLES.MEMBER)).toBe(4);
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
      expect(isSameOrHigherRole(GROUP_ROLES.LEADER, GROUP_ROLES.MEMBER)).toBe(true);
      expect(isSameOrHigherRole(GROUP_ROLES.CO_LEADER, GROUP_ROLES.CO_LEADER)).toBe(true);
      expect(isSameOrHigherRole(GROUP_ROLES.ELDER, GROUP_ROLES.MEMBER)).toBe(true);
      expect(isSameOrHigherRole(GROUP_ROLES.MEMBER, GROUP_ROLES.ELDER)).toBe(false);
      expect(isSameOrHigherRole(GROUP_ROLES.ELDER, GROUP_ROLES.LEADER)).toBe(false);
    });
  });

  describe("normalizeGroupRole", () => {
    it("keeps valid roles and defaults others to MEMBER", () => {
      expect(normalizeGroupRole(GROUP_ROLES.LEADER)).toBe(GROUP_ROLES.LEADER);
      expect(normalizeGroupRole(GROUP_ROLES.CO_LEADER)).toBe(GROUP_ROLES.CO_LEADER);
      expect(normalizeGroupRole(GROUP_ROLES.ELDER)).toBe(GROUP_ROLES.ELDER);
      expect(normalizeGroupRole(GROUP_ROLES.MEMBER)).toBe(GROUP_ROLES.MEMBER);
      expect(normalizeGroupRole(null)).toBe(GROUP_ROLES.MEMBER);
      expect(normalizeGroupRole("ADMIN")).toBe(GROUP_ROLES.MEMBER);
    });
  });

  describe("formatGroupRoleLabel", () => {
    it("formats underscores as spaces", () => {
      expect(formatGroupRoleLabel(GROUP_ROLES.CO_LEADER)).toBe("CO LEADER");
      expect(formatGroupRoleLabel(GROUP_ROLES.LEADER)).toBe("LEADER");
      expect(formatGroupRoleLabel(undefined)).toBe("MEMBER");
    });
  });

  describe("getAssignableGroupRoles", () => {
    it("excludes LEADER and only allows same-or-lower privilege roles", () => {
      expect(getAssignableGroupRoles(GROUP_ROLES.LEADER)).toEqual([
        GROUP_ROLES.CO_LEADER,
        GROUP_ROLES.ELDER,
        GROUP_ROLES.MEMBER,
      ]);
      expect(getAssignableGroupRoles(GROUP_ROLES.CO_LEADER)).toEqual([
        GROUP_ROLES.CO_LEADER,
        GROUP_ROLES.ELDER,
        GROUP_ROLES.MEMBER,
      ]);
      expect(getAssignableGroupRoles(GROUP_ROLES.ELDER)).toEqual([
        GROUP_ROLES.ELDER,
        GROUP_ROLES.MEMBER,
      ]);
      expect(getAssignableGroupRoles(GROUP_ROLES.MEMBER)).toEqual([GROUP_ROLES.MEMBER]);
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
      actorRole: GROUP_ROLES.CO_LEADER,
      actorPermissions: ["KICK_MEMBERS"],
      targetUsername: "bob",
      targetRole: GROUP_ROLES.MEMBER,
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
          targetRole: GROUP_ROLES.LEADER,
        }),
      ).toBe(false);
    });

    it("returns false when the target has a higher role", () => {
      expect(
        canPreviewManageTarget({
          ...baseArgs,
          actorRole: GROUP_ROLES.ELDER,
          targetRole: GROUP_ROLES.CO_LEADER,
        }),
      ).toBe(false);
    });

    it("allows same-rank co-leaders to manage each other", () => {
      expect(
        canPreviewManageTarget({
          ...baseArgs,
          actorRole: GROUP_ROLES.CO_LEADER,
          targetRole: GROUP_ROLES.CO_LEADER,
          requiredPermission: "KICK_MEMBERS",
        }),
      ).toBe(true);
    });

    it("treats an unknown actor role as MEMBER so they cannot manage elders", () => {
      expect(
        canPreviewManageTarget({
          ...baseArgs,
          actorRole: "ADMIN",
          actorPermissions: ["KICK_MEMBERS"],
          targetRole: GROUP_ROLES.ELDER,
        }),
      ).toBe(false);
    });
  });
});
