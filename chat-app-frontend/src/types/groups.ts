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

// Similar to chat-app-backend/src/main/java/com/hello/chatapp/dto/GroupSummaryUpdate.java
export interface GroupSummaryUpdate {
  groupId: number | string;
  latestMessage?: string | null;
  latestMessageSender?: string | null;
  latestMessageAt?: string | null;
  // TODO: Confirm whether backend should eventually send authoritative unreadCount here.
  unreadCount?: number;
}
