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

  it("updates name and description from a newer group profile event", () => {
    const nextGroups = applyGroupSummaryUpdate(
      baseGroups,
      {
        groupId: 1,
        name: "Alpha Prime",
        description: "Renamed team",
        latestMessage: "Group name updated",
        latestMessageSender: "System",
        latestMessageAt: "2026-08-01T12:30:00Z",
      },
      "public",
    );

    expect(nextGroups[0]).toMatchObject({
      id: 1,
      name: "Alpha Prime",
      description: "Renamed team",
      latestMessage: "Group name updated",
      unreadCount: 3,
    });
  });

  it("drops a group when removed is true", () => {
    const nextGroups = applyGroupSummaryUpdate(
      baseGroups,
      { groupId: 1, removed: true },
      1,
    );

    expect(nextGroups).toHaveLength(1);
    expect(nextGroups[0].id).toBe(2);
  });

  it("ignores an unknown group that has no name and no latest message", () => {
    const nextGroups = applyGroupSummaryUpdate(
      baseGroups,
      {
        groupId: 99,
        currentUserRole: GROUP_ROLES.MEMBER,
        currentUserPermissions: ["SEND_MESSAGES"],
      },
      "public",
    );

    expect(nextGroups).toBe(baseGroups);
  });

  it("merges access fields without bumping unread when latest-message fields are omitted", () => {
    const nextGroups = applyGroupSummaryUpdate(
      baseGroups,
      {
        groupId: 1,
        currentUserRole: GROUP_ROLES.ELDER,
        currentUserPermissions: ["SEND_MESSAGES", "KICK_MEMBERS"],
      },
      "public",
    );

    expect(nextGroups[0]).toMatchObject({
      id: 1,
      name: "Alpha",
      latestMessage: "Older",
      unreadCount: 2,
      currentUserRole: GROUP_ROLES.ELDER,
      currentUserPermissions: ["SEND_MESSAGES", "KICK_MEMBERS"],
    });
    expect(nextGroups[1].id).toBe(2);
  });

  it("moves a newer inactive chat to the front and increments unread", () => {
    const nextGroups = applyGroupSummaryUpdate(
      baseGroups,
      {
        groupId: 1,
        latestMessage: "Hello",
        latestMessageSender: "bob",
        latestMessageAt: "2026-08-01T12:00:00Z",
      },
      "public",
    );

    expect(nextGroups.map((group) => group.id)).toEqual([1, 2]);
    expect(nextGroups[0]).toMatchObject({
      latestMessage: "Hello",
      unreadCount: 3,
    });
  });

  it("keeps unread at zero when the active chat receives a newer preview", () => {
    const nextGroups = applyGroupSummaryUpdate(
      baseGroups,
      {
        groupId: 1,
        latestMessage: "While open",
        latestMessageSender: "bob",
        latestMessageAt: "2026-08-01T12:00:00Z",
      },
      1,
    );

    expect(nextGroups[0]).toMatchObject({
      id: 1,
      latestMessage: "While open",
      unreadCount: 0,
    });
  });
});
