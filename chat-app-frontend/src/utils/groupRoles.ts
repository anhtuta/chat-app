import type { GroupRole } from "../types/groups";

const ROLE_RANK: Record<string, number> = {
  LEADER: 1,
  CO_LEADER: 2,
  ELDER: 3,
  MEMBER: 4,
};

export function getRoleRank(role: string | null | undefined): number {
  if (!role) {
    return ROLE_RANK.MEMBER;
  }
  return ROLE_RANK[role] ?? ROLE_RANK.MEMBER;
}

/** Lower rank number means higher privilege (matches backend GroupRole). */
export function isSameOrHigherRole(
  actorRole: string | null | undefined,
  targetRole: string | null | undefined,
): boolean {
  return getRoleRank(actorRole) <= getRoleRank(targetRole);
}

export function normalizeGroupRole(role: string | null | undefined): GroupRole {
  if (role === "LEADER" || role === "CO_LEADER" || role === "ELDER" || role === "MEMBER") {
    return role;
  }
  return "MEMBER";
}

export function formatGroupRoleLabel(role: string | null | undefined): string {
  return normalizeGroupRole(role).replace(/_/g, " ");
}

/**
 * Prefer the strongest target-management permission the actor actually has,
 * so "Manageable" previews align with kick/ban/role flows.
 */
export function resolveManagePermissionPreview(permissions: string[]): string {
  if (permissions.includes("MANAGE_ROLES")) {
    return "MANAGE_ROLES";
  }
  if (permissions.includes("BAN_MEMBERS")) {
    return "BAN_MEMBERS";
  }
  if (permissions.includes("KICK_MEMBERS")) {
    return "KICK_MEMBERS";
  }
  return "KICK_MEMBERS";
}

/**
 * Preview whether the current actor could manage a target under Phase 3 rules.
 * Does not call the backend; used only for Phase 8 visibility cues.
 * Manageable = the current user could act on that member (kick/ban/role change) —
 * same-or-lower rank, not self, not the leader, and they have a management permission.
 * Out of reach = they can see moderation in general, but not for that target
 * (e.g. an elder looking at a co-leader).
 * @returns true if manageable, false if out of reach
 */
export function canPreviewManageTarget({
  actorUsername,
  actorRole,
  actorPermissions,
  targetUsername,
  targetRole,
  requiredPermission,
}: {
  actorUsername?: string | null;
  actorRole?: string | null;
  actorPermissions: string[];
  targetUsername?: string | null;
  targetRole?: string | null;
  requiredPermission: string;
}): boolean {
  if (!actorPermissions.includes(requiredPermission)) {
    return false;
  }
  if (actorUsername && targetUsername && actorUsername === targetUsername) {
    return false;
  }
  if (normalizeGroupRole(targetRole) === "LEADER") {
    return false;
  }
  return isSameOrHigherRole(actorRole, targetRole);
}
