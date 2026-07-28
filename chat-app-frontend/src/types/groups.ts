export interface ChatGroup {
  id: number;
  name: string;
  description?: string | null;
  latestMessage?: string | null;
  latestMessageSender?: string | null;
  latestMessageAt?: string | null;
  unreadCount?: number;
  currentUserRole?: string | null;
  currentUserPermissions?: string[];
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

export type GroupSummaryUpdateAction = "UPSERT" | "REMOVE";

// Similar to chat-app-backend/src/main/java/com/hello/chatapp/dto/GroupSummaryUpdate.java
export interface GroupSummaryUpdate {
  groupId: number | string;
  action?: GroupSummaryUpdateAction;
  name?: string | null;
  description?: string | null;
  latestMessage?: string | null;
  latestMessageSender?: string | null;
  latestMessageAt?: string | null;
  currentUserRole?: string | null;
  currentUserPermissions?: string[];
  // TODO: Confirm whether backend should eventually send authoritative unreadCount here.
  unreadCount?: number;
}
