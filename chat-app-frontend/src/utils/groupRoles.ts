import {
  GROUP_ROLES,
  GROUP_ROLE_RANK,
  GROUP_ROLE_VALUES,
  isGroupRole,
  type GroupRole,
} from "../constant/groupRoles";

export function getRoleRank(role: string | null | undefined): number {
  if (!role || !isGroupRole(role)) {
    return GROUP_ROLE_RANK[GROUP_ROLES.MEMBER];
  }
  return GROUP_ROLE_RANK[role];
}

/** Lower rank number means higher privilege (matches backend GroupRole). */
export function isSameOrHigherRole(
  actorRole: string | null | undefined,
  targetRole: string | null | undefined,
): boolean {
  return getRoleRank(actorRole) <= getRoleRank(targetRole);
}

export function normalizeGroupRole(role: string | null | undefined): GroupRole {
  if (isGroupRole(role)) {
    return role;
  }
  return GROUP_ROLES.MEMBER;
}

export function formatGroupRoleLabel(role: string | null | undefined): string {
  return normalizeGroupRole(role).replace(/_/g, " ");
}

/**
 * Roles that can be assigned via PATCH (never LEADER — use leadership transfer).
 * Only same-or-lower privilege than the actor (higher rank number).
 */
export function getAssignableGroupRoles(actorRole: string | null | undefined): GroupRole[] {
  const actorRank = getRoleRank(actorRole);
  return GROUP_ROLE_VALUES.filter(
    (role) => role !== GROUP_ROLES.LEADER && GROUP_ROLE_RANK[role] >= actorRank,
  );
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
  if (normalizeGroupRole(targetRole) === GROUP_ROLES.LEADER) {
    return false;
  }
  return isSameOrHigherRole(actorRole, targetRole);
}
