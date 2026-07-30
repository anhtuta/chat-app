export type GroupRole = "LEADER" | "CO_LEADER" | "ELDER" | "MEMBER";

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

export interface GroupMember {
  userId: number;
  username: string;
  fullname?: string | null;
  role: GroupRole | string;
  joinedAt?: string | null;
}

export interface GroupMemberPage {
  content: GroupMember[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  hasNext: boolean;
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
