/**
 * Group roles — mirrors chat-app-backend `GroupRole`.
 * Lower rank number means higher privilege.
 */
export const GROUP_ROLES = {
  LEADER: "LEADER",
  CO_LEADER: "CO_LEADER",
  ELDER: "ELDER",
  MEMBER: "MEMBER",
} as const;

export type GroupRole = (typeof GROUP_ROLES)[keyof typeof GROUP_ROLES];

export const GROUP_ROLE_VALUES: readonly GroupRole[] = Object.values(GROUP_ROLES);

export const GROUP_ROLE_RANK: Record<GroupRole, number> = {
  [GROUP_ROLES.LEADER]: 1,
  [GROUP_ROLES.CO_LEADER]: 2,
  [GROUP_ROLES.ELDER]: 3,
  [GROUP_ROLES.MEMBER]: 4,
};

export function isGroupRole(value: string | null | undefined): value is GroupRole {
  return Boolean(value && (GROUP_ROLE_VALUES as readonly string[]).includes(value));
}
