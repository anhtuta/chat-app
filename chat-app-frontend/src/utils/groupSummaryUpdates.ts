import type { ChatGroup, GroupSummaryUpdate } from "../types/groups";

function toEpochMillis(value: string | null | undefined): number {
  if (!value) {
    return 0;
  }
  const parsed = Date.parse(value);
  return Number.isNaN(parsed) ? 0 : parsed;
}

function hasLatestMessageFields(groupSummaryUpdate: GroupSummaryUpdate): boolean {
  return (
    groupSummaryUpdate.latestMessageAt !== undefined
    || groupSummaryUpdate.latestMessage !== undefined
    || groupSummaryUpdate.latestMessageSender !== undefined
  );
}

export function applyGroupSummaryUpdate(
  previousGroups: ChatGroup[],
  groupSummaryUpdate: GroupSummaryUpdate,
  activeChatId: number | "public",
): ChatGroup[] {
  const updatedGroupId = Number(groupSummaryUpdate.groupId);

  if (groupSummaryUpdate.removed) {
    return previousGroups.filter((group) => Number(group.id) !== updatedGroupId);
  }

  const groupIndex = previousGroups.findIndex((group) => Number(group.id) === updatedGroupId);
  if (groupIndex === -1) {
    if (!groupSummaryUpdate.name && !groupSummaryUpdate.latestMessage) {
      return previousGroups;
    }
    const insertedGroup: ChatGroup = {
      id: updatedGroupId,
      name: groupSummaryUpdate.name || `Group ${updatedGroupId}`,
      description: groupSummaryUpdate.description ?? null,
      latestMessage: groupSummaryUpdate.latestMessage,
      latestMessageSender: groupSummaryUpdate.latestMessageSender,
      latestMessageAt: groupSummaryUpdate.latestMessageAt,
      unreadCount: activeChatId === updatedGroupId ? 0 : 1,
      currentUserRole: groupSummaryUpdate.currentUserRole ?? null,
      currentUserPermissions: groupSummaryUpdate.currentUserPermissions,
    };
    return [insertedGroup, ...previousGroups];
  }

  const currentGroup = previousGroups[groupIndex];
  const mergedGroup: ChatGroup = {
    ...currentGroup,
    name: groupSummaryUpdate.name || currentGroup.name,
    description: groupSummaryUpdate.description ?? currentGroup.description,
    currentUserRole: groupSummaryUpdate.currentUserRole ?? currentGroup.currentUserRole,
    currentUserPermissions: groupSummaryUpdate.currentUserPermissions ?? currentGroup.currentUserPermissions,
  };

  if (!hasLatestMessageFields(groupSummaryUpdate)) {
    return previousGroups.map((group, index) => (index === groupIndex ? mergedGroup : group));
  }

  const incomingTimestamp = toEpochMillis(groupSummaryUpdate.latestMessageAt);
  const currentTimestamp = toEpochMillis(currentGroup.latestMessageAt);
  if (incomingTimestamp < currentTimestamp) {
    return previousGroups.map((group, index) => (index === groupIndex ? mergedGroup : group));
  }

  const latestMergedGroup: ChatGroup = {
    ...mergedGroup,
    latestMessage: groupSummaryUpdate.latestMessage ?? currentGroup.latestMessage,
    latestMessageSender: groupSummaryUpdate.latestMessageSender ?? currentGroup.latestMessageSender,
    latestMessageAt: groupSummaryUpdate.latestMessageAt ?? currentGroup.latestMessageAt,
    unreadCount: incomingTimestamp === currentTimestamp
      ? currentGroup.unreadCount
      : (activeChatId === updatedGroupId ? 0 : Number(currentGroup.unreadCount || 0) + 1),
  };

  if (incomingTimestamp === currentTimestamp) {
    return previousGroups.map((group, index) => (index === groupIndex ? latestMergedGroup : group));
  }

  return [
    latestMergedGroup,
    ...previousGroups.slice(0, groupIndex),
    ...previousGroups.slice(groupIndex + 1),
  ];
}
