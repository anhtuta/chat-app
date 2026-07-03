export interface ChatGroup {
  id: number;
  name: string;
  latestMessage?: string | null;
  latestMessageSender?: string | null;
  latestMessageAt?: string | null;
  unreadCount?: number;
}

export interface GroupSummaryUpdate {
  groupId: number | string;
  latestMessage?: string | null;
  latestMessageSender?: string | null;
  latestMessageAt?: string | null;
  // TODO: Confirm whether backend should eventually send authoritative unreadCount here.
  unreadCount?: number;
}
