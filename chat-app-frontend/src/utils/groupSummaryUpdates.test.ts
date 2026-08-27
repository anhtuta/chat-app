import { GROUP_ROLES } from "../constant/groupRoles";
import type { ChatGroup } from "../types/groups";
import { applyGroupSummaryUpdate } from "./groupSummaryUpdates";

describe("groupSummaryUpdates", () => {
  const baseGroups: ChatGroup[] = [
    {
      id: 1,
      name: "Alpha",
      latestMessage: "Older",
      latestMessageSender: "alice",
      latestMessageAt: "2026-08-01T10:00:00Z",
      unreadCount: 2,
      currentUserRole: GROUP_ROLES.MEMBER,
      currentUserPermissions: ["SEND_MESSAGES"],
    },
    {
      id: 2,
      name: "Beta",
      latestMessage: "Newest",
      latestMessageSender: "System",
      latestMessageAt: "2026-08-01T11:00:00Z",
      unreadCount: 0,
      currentUserRole: GROUP_ROLES.LEADER,
      currentUserPermissions: ["MANAGE_ROLES"],
    },
  ];

  it("inserts an unknown group from a realtime membership update", () => {
    const nextGroups = applyGroupSummaryUpdate(
      baseGroups,
      {
        groupId: 3,
        name: "Gamma",
        latestMessage: "Member joined",
        latestMessageSender: "System",
        latestMessageAt: "2026-08-01T12:00:00Z",
      },
      "public",
    );

    expect(nextGroups[0]).toMatchObject({
      id: 3,
      name: "Gamma",
      latestMessage: "Member joined",
      unreadCount: 1,
    });
  });

  it("updates role and permissions without double-incrementing unread on duplicate timestamps", () => {
    const nextGroups = applyGroupSummaryUpdate(
      baseGroups,
      {
        groupId: 1,
        name: "Alpha",
        latestMessage: "Member promoted",
        latestMessageSender: "System",
        latestMessageAt: "2026-08-01T10:00:00Z",
        currentUserRole: GROUP_ROLES.CO_LEADER,
        currentUserPermissions: ["SEND_MESSAGES", "MANAGE_ROLES"],
      },
      "public",
    );

    expect(nextGroups[0]).toMatchObject({
      id: 1,
      currentUserRole: GROUP_ROLES.CO_LEADER,
      currentUserPermissions: ["SEND_MESSAGES", "MANAGE_ROLES"],
      unreadCount: 2,
    });
  });

  it("keeps the latest preview while still merging stale access updates", () => {
    const nextGroups = applyGroupSummaryUpdate(
      baseGroups,
      {
        groupId: 2,
        currentUserRole: GROUP_ROLES.MEMBER,
        currentUserPermissions: ["SEND_MESSAGES"],
        latestMessage: "Leadership transferred",
        latestMessageSender: "System",
        latestMessageAt: "2026-08-01T09:00:00Z",
      },
      "public",
    );

    expect(nextGroups[1]).toMatchObject({
      id: 2,
      latestMessage: "Newest",
      latestMessageAt: "2026-08-01T11:00:00Z",
      currentUserRole: GROUP_ROLES.MEMBER,
      currentUserPermissions: ["SEND_MESSAGES"],
    });
  });
});
