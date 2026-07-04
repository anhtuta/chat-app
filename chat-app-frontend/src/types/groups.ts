export interface ChatGroup {
  id: number;
  name: string;
  latestMessage?: string | null;
  latestMessageSender?: string | null;
  latestMessageAt?: string | null;
  unreadCount?: number;
}

export interface SelectableUser {
  id: number;
  username: string;
  fullname?: string | null;
  createdAt?: string | null;
}

export interface UnreadSummaryResponse {
  totalUnreadCount: number;
}

// Similar to chat-app-backend/src/main/java/com/hello/chatapp/dto/GroupSummaryUpdate.java
export interface GroupSummaryUpdate {
  groupId: number | string;
  latestMessage?: string | null;
  latestMessageSender?: string | null;
  latestMessageAt?: string | null;
  // TODO: Confirm whether backend should eventually send authoritative unreadCount here.
  unreadCount?: number;
}
