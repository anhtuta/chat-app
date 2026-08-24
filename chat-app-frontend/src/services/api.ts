/**
 * API service for communicating with Spring Boot backend
 * In development: requests are proxied via "proxy" in package.json
 * In production: requests go directly to the backend
 */
import type { AuthCheckResponse, LoginResponse, RegisterResponse } from "../types/auth";
import type {
  ChatMessage,
  CompleteMediaAttachmentInput,
  GroupMessagesQuery,
  PrepareMediaMessageRequest,
  PrepareMediaMessageResponse,
  RequestMultipartPartUrlsResponse,
} from "../types/chat";
import type { ChatGroup, GroupBan, GroupJoinLink, GroupMember, GroupMemberPage, GroupRole, SelectableUser } from "../types/groups";

const API_BASE_URL = "";

export interface UploadProgressOptions {
  onProgress?: (loadedBytes: number, totalBytes: number) => void;
}

export interface UploadHandle {
  promise: Promise<string>;
  abort: () => void;
}

/**
 * Check if user is authenticated
 */
export async function checkAuth(): Promise<AuthCheckResponse> {
  const response = await fetch(`${API_BASE_URL}/api/auth/check`, {
    credentials: "include",
  });
  return response.json();
}

/**
 * Login
 */
export async function login(username: string, password: string): Promise<LoginResponse> {
  const response = await fetch(`${API_BASE_URL}/api/auth/login`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
    },
    credentials: "include",
    body: JSON.stringify({ username, password }),
  });
  return response.json();
}

/**
 * Register
 */
export async function register(username: string, password: string): Promise<RegisterResponse> {
  const response = await fetch(`${API_BASE_URL}/api/auth/register`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
    },
    credentials: "include",
    body: JSON.stringify({ username, password }),
  });
  return response.json();
}

/**
 * Logout user
 */
export async function logout(): Promise<Response> {
  const response = await fetch(`${API_BASE_URL}/api/auth/logout`, {
    method: "POST",
    credentials: "include",
  });
  return response;
}

/**
 * Get all groups for the current user
 */
export async function getGroups(): Promise<ChatGroup[]> {
  const response = await fetch(`${API_BASE_URL}/api/groups`, {
    credentials: "include",
  });
  if (response.ok) {
    return response.json();
  }
  return handleErrorResponse(response);
}

/**
 * Get details for a single group visible to the current member.
 */
export async function getGroupDetails(groupId: number | string): Promise<ChatGroup> {
  const response = await fetch(`${API_BASE_URL}/api/groups/${groupId}`, {
    credentials: "include",
  });
  if (response.ok) {
    return response.json();
  }
  throw new Error(await response.text() || "Failed to load group details");
}

/**
 * Update editable group metadata for the current member's group.
 */
export async function updateGroupDetails(
  groupId: number | string,
  { name, description }: { name?: string; description?: string | null },
): Promise<ChatGroup> {
  const response = await fetch(`${API_BASE_URL}/api/groups/${groupId}`, {
    method: "PATCH",
    headers: {
      "Content-Type": "application/json",
    },
    credentials: "include",
    body: JSON.stringify({
      ...(name !== undefined ? { name } : {}),
      ...(description !== undefined ? { description } : {}),
    }),
  });
  if (response.ok) {
    return response.json();
  }
  throw new Error(await response.text() || "Failed to update group details");
}

/**
 * List members for a group visible to the current member.
 * Supports optional search (`q`) and pagination (`page`, `size`; default size 100).
 */
export async function getGroupMembers(
  groupId: number | string,
  {
    q,
    page = 0,
    size = 100,
  }: {
    q?: string;
    page?: number;
    size?: number;
  } = {},
): Promise<GroupMemberPage> {
  const queryParams = new URLSearchParams({
    page: String(page),
    size: String(size),
  });
  if (q?.trim()) {
    queryParams.set("q", q.trim());
  }

  const response = await fetch(
    `${API_BASE_URL}/api/groups/${groupId}/members?${queryParams.toString()}`,
    {
      credentials: "include",
    },
  );
  if (response.ok) {
    return response.json();
  }
  throw new Error(await response.text() || "Failed to load group members");
}

/**
 * Add one or more users directly to a group as MEMBER.
 */
export async function addGroupMembers(
  groupId: number | string,
  userIds: number[],
): Promise<GroupMember[]> {
  const response = await fetch(`${API_BASE_URL}/api/groups/${groupId}/members`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
    },
    credentials: "include",
    body: JSON.stringify({ userIds }),
  });
  if (response.ok) {
    return response.json();
  }
  throw new Error((await response.text()) || "Failed to add group members");
}

/**
 * Kick/remove a member from a group.
 */
export async function kickGroupMember(
  groupId: number | string,
  userId: number,
): Promise<void> {
  const response = await fetch(`${API_BASE_URL}/api/groups/${groupId}/members/${userId}`, {
    method: "DELETE",
    credentials: "include",
  });
  if (response.ok) {
    return;
  }
  throw new Error(await response.text() || "Failed to remove group member");
}

/**
 * Promote/demote a member role. Cannot assign LEADER — use transferLeadership.
 * Requires MANAGE_ROLES.
 */
export async function updateGroupMemberRole(
  groupId: number | string,
  userId: number,
  role: GroupRole,
): Promise<GroupMember> {
  const response = await fetch(`${API_BASE_URL}/api/groups/${groupId}/members/${userId}/role`, {
    method: "PATCH",
    headers: {
      "Content-Type": "application/json",
    },
    credentials: "include",
    body: JSON.stringify({ role }),
  });
  if (response.ok) {
    return response.json();
  }
  throw new Error(await response.text() || "Failed to update member role");
}

/**
 * Transfer leadership to another member. Requires current user to be LEADER.
 * Previous leader becomes MEMBER.
 */
export async function transferGroupLeadership(
  groupId: number | string,
  newLeaderUserId: number,
): Promise<void> {
  const response = await fetch(`${API_BASE_URL}/api/groups/${groupId}/leadership-transfer`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
    },
    credentials: "include",
    body: JSON.stringify({ newLeaderUserId }),
  });
  if (response.ok) {
    return;
  }
  throw new Error(await response.text() || "Failed to transfer leadership");
}

/**
 * Ban a user from a group (removes membership if present).
 */
export async function banGroupMember(
  groupId: number | string,
  userId: number,
  reason?: string,
): Promise<void> {
  const response = await fetch(`${API_BASE_URL}/api/groups/${groupId}/bans`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
    },
    credentials: "include",
    body: JSON.stringify({
      userId,
      reason: reason?.trim() || undefined,
    }),
  });
  if (response.ok) {
    return;
  }
  throw new Error(await response.text() || "Failed to ban group member");
}

/**
 * List banned users for a group. Requires UNBAN_MEMBERS.
 */
export async function getGroupBans(groupId: number | string): Promise<GroupBan[]> {
  const response = await fetch(`${API_BASE_URL}/api/groups/${groupId}/bans`, {
    credentials: "include",
  });
  if (response.ok) {
    return response.json();
  }
  throw new Error(await response.text() || "Failed to load banned users");
}

/**
 * Unban a user from a group. Requires UNBAN_MEMBERS.
 */
export async function unbanGroupMember(
  groupId: number | string,
  userId: number,
): Promise<void> {
  const response = await fetch(`${API_BASE_URL}/api/groups/${groupId}/bans/${userId}`, {
    method: "DELETE",
    credentials: "include",
  });
  if (response.ok) {
    return;
  }
  throw new Error(await response.text() || "Failed to unban group member");
}

/**
 * Leave the current group as the authenticated user.
 * Leaders with other members remaining must transfer leadership first.
 * Last-member leave archives the group.
 */
export async function leaveGroup(groupId: number | string): Promise<void> {
  const response = await fetch(`${API_BASE_URL}/api/groups/${groupId}/members/me`, {
    method: "DELETE",
    credentials: "include",
  });
  if (response.ok) {
    return;
  }
  // Prefer body text so business-rule 403s (e.g. transfer leadership first) surface in the UI
  // instead of the generic auth redirect in handleErrorResponse.
  throw new Error(await response.text() || "Failed to leave group");
}

/**
 * List join links for a group (metadata only). Requires CREATE_JOIN_LINK.
 */
export async function getGroupJoinLinks(groupId: number | string): Promise<GroupJoinLink[]> {
  const response = await fetch(`${API_BASE_URL}/api/groups/${groupId}/join-links`, {
    credentials: "include",
  });
  if (response.ok) {
    return response.json();
  }
  throw new Error(await response.text() || "Failed to load join links");
}

/**
 * Create a join link. Requires CREATE_JOIN_LINK.
 * Returns the raw token once; subsequent list calls omit it.
 */
export async function createGroupJoinLink(
  groupId: number | string,
  expiresAt?: string | null,
): Promise<GroupJoinLink> {
  const response = await fetch(`${API_BASE_URL}/api/groups/${groupId}/join-links`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
    },
    credentials: "include",
    body: JSON.stringify({
      expiresAt: expiresAt || undefined,
    }),
  });
  if (response.ok) {
    return response.json();
  }
  throw new Error(await response.text() || "Failed to create join link");
}

/**
 * Revoke a join link. Requires CREATE_JOIN_LINK.
 */
export async function revokeGroupJoinLink(
  groupId: number | string,
  joinLinkId: number,
): Promise<void> {
  const response = await fetch(`${API_BASE_URL}/api/groups/${groupId}/join-links/${joinLinkId}`, {
    method: "DELETE",
    credentials: "include",
  });
  if (response.ok) {
    return;
  }
  throw new Error(await response.text() || "Failed to revoke join link");
}

/**
 * Join a group using a join-link token.
 * Returns membership details including groupId/groupName for navigation.
 */
export async function joinGroupByToken(token: string): Promise<GroupMember> {
  const normalizedToken = token.trim();
  if (!normalizedToken) {
    throw new Error("Join token is required");
  }

  const response = await fetch(
    `${API_BASE_URL}/api/groups/join-links/${encodeURIComponent(normalizedToken)}/join`,
    {
      method: "POST",
      credentials: "include",
    },
  );
  if (response.ok) {
    return response.json();
  }
  // Prefer body text so business-rule 403s (e.g. banned) surface in the UI
  // instead of the generic auth redirect in handleErrorResponse.
  throw new Error(await response.text() || "Failed to join group");
}

/**
 * Get public chat messages
 */
export async function getPublicMessages(): Promise<ChatMessage[]> {
  const response = await fetch(`${API_BASE_URL}/api/messages/public`, {
    credentials: "include",
  });
  if (response.ok) {
    return response.json();
  }
  return handleErrorResponse(response);
}

/**
 * Get group messages
 */
export async function getGroupMessages(
  groupId: number | string,
  { beforeTimestamp, beforeId, size = 10 }: GroupMessagesQuery = {},
): Promise<ChatMessage[]> {
  const queryParams = new URLSearchParams({ size: String(size) });

  if (beforeTimestamp) {
    queryParams.set("beforeTimestamp", beforeTimestamp);
  }

  if (beforeId !== undefined && beforeId !== null) {
    queryParams.set("beforeId", String(beforeId));
  }

  const response = await fetch(
    `${API_BASE_URL}/api/messages/groups/${groupId}?${queryParams.toString()}`,
    {
      credentials: "include",
    },
  );
  if (response.ok) {
    return response.json();
  }
  return handleErrorResponse(response);
}

/**
 * Get all users (for creating groups)
 */
export async function getUsers(): Promise<SelectableUser[]> {
  const response = await fetch(`${API_BASE_URL}/api/groups/users`, {
    credentials: "include",
  });
  if (response.ok) {
    return response.json();
  }
  return handleErrorResponse(response);
}

/**
 * Users who can be added to a group (not already members, not banned).
 * Requires ADD_MEMBERS. Optional `q` searches username/fullname. Capped at 500 (no pagination).
 */
export async function getAddableGroupUsers(
  groupId: number | string,
  { q }: { q?: string } = {},
): Promise<SelectableUser[]> {
  const queryParams = new URLSearchParams();
  if (q?.trim()) {
    queryParams.set("q", q.trim());
  }
  const query = queryParams.toString();
  const response = await fetch(
    `${API_BASE_URL}/api/groups/${groupId}/addable-users${query ? `?${query}` : ""}`,
    {
      credentials: "include",
    },
  );
  if (response.ok) {
    return response.json();
  }
  return handleErrorResponse(response);
}

/**
 * Create a new group
 */
export async function createGroup(
  name: string,
  participantIds: number[],
  description?: string,
): Promise<ChatGroup> {
  const response = await fetch(`${API_BASE_URL}/api/groups`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
    },
    credentials: "include",
    body: JSON.stringify({
      name,
      description,
      participantIds,
    }),
  });
  if (response.ok) {
    return response.json();
  }
  const error = await response.text();
  throw new Error(error || "Failed to create group");
}

/**
 * Mark a group as read up to the provided latest visible message id.
 */
export async function markGroupAsRead(groupId: number | string, lastReadMessageId: number): Promise<void> {
  const response = await fetch(`${API_BASE_URL}/api/groups/${groupId}/read`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
    },
    credentials: "include",
    body: JSON.stringify({ lastReadMessageId }),
  });

  if (response.ok) {
    return;
  }
  return handleErrorResponse(response);
}

/**
 * Edit an existing text message.
 */
export async function updateMessage(messageId: number | string, content: string): Promise<ChatMessage> {
  const response = await fetch(`${API_BASE_URL}/api/messages/${messageId}`, {
    method: "PATCH",
    headers: {
      "Content-Type": "application/json",
    },
    credentials: "include",
    body: JSON.stringify({ content }),
  });

  if (response.ok) {
    return response.json();
  }
  return handleErrorResponse(response);
}

/**
 * Soft-delete an existing message.
 */
export async function deleteMessage(messageId: number | string): Promise<ChatMessage> {
  const response = await fetch(`${API_BASE_URL}/api/messages/${messageId}`, {
    method: "DELETE",
    credentials: "include",
  });

  if (response.ok) {
    return response.json();
  }
  return handleErrorResponse(response);
}

/**
 * Prepare a media message upload session.
 */
export async function prepareMediaMessage(
  request: PrepareMediaMessageRequest,
): Promise<PrepareMediaMessageResponse> {
  const response = await fetch(`${API_BASE_URL}/api/media/messages/prepare`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
    },
    credentials: "include",
    body: JSON.stringify(request),
  });

  if (response.ok) {
    return response.json();
  }

  throw new Error(await readErrorMessage(response, "Failed to prepare media message"));
}

/**
 * Request presigned upload URLs for selected multipart part numbers.
 */
export async function requestMultipartPartUrls(
  uploadSessionId: string,
  attachmentId: string,
  partNumbers: number[],
): Promise<RequestMultipartPartUrlsResponse> {
  const response = await fetch(
    `${API_BASE_URL}/api/media/messages/upload-sessions/${encodeURIComponent(uploadSessionId)}` +
      `/attachments/${encodeURIComponent(attachmentId)}/parts`,
    {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
      },
      credentials: "include",
      body: JSON.stringify({ partNumbers }),
    },
  );

  if (response.ok) {
    return response.json();
  }

  throw new Error(await readErrorMessage(response, "Failed to prepare multipart upload parts"));
}

/**
 * Complete a prepared media upload session and publish the final message.
 */
export async function completeMediaMessage(
  uploadSessionId: string,
  attachments: CompleteMediaAttachmentInput[],
): Promise<ChatMessage> {
  const response = await fetch(
    `${API_BASE_URL}/api/media/messages/upload-sessions/${uploadSessionId}/complete`,
    {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
      },
      credentials: "include",
      body: JSON.stringify({ attachments }),
    },
  );

  if (response.ok) {
    return response.json();
  }

  throw new Error(await readErrorMessage(response, "Failed to complete media message"));
}

/**
 * Upload bytes directly to a storage-provider presigned URL.
 */
export function uploadBlobToPresignedUrl(
  url: string,
  blob: Blob,
  { onProgress }: UploadProgressOptions = {},
): UploadHandle {
  const xhr = new XMLHttpRequest();

  const promise = new Promise<string>((resolve, reject) => {
    xhr.upload.addEventListener("progress", (event) => {
      if (event.lengthComputable && onProgress) {
        onProgress(event.loaded, event.total);
      }
    });

    xhr.addEventListener("load", () => {
      if (xhr.status >= 200 && xhr.status < 300) {
        // Some storage CORS configs do not expose ETag to browsers yet.
        resolve(xhr.getResponseHeader("ETag") || xhr.getResponseHeader("etag") || "etag-unavailable");
        return;
      }

      reject(new Error(`Upload failed with status ${xhr.status}`));
    });

    xhr.addEventListener("error", () => {
      reject(new Error("Upload failed"));
    });

    xhr.addEventListener("abort", () => {
      const abortError = new Error("Upload canceled");
      abortError.name = "AbortError";
      reject(abortError);
    });

    xhr.open("PUT", url);
    if (blob.type) {
      xhr.setRequestHeader("Content-Type", blob.type);
    }
    xhr.send(blob);
  });

  return {
    promise,
    abort: () => xhr.abort(),
  };
}

/**
 * Upload a file directly to a storage-provider presigned URL.
 */
export function uploadFileToPresignedUrl(
  url: string,
  file: File,
  options: UploadProgressOptions = {},
): UploadHandle {
  return uploadBlobToPresignedUrl(url, file, options);
}

function handleErrorResponse(response: Response): never {
  if (response.status === 403) {
    window.location.href = "/login";
  }
  throw new Error(`API error: ${response.status} ${response.statusText}`);
}

async function readErrorMessage(response: Response, fallbackMessage: string): Promise<string> {
  if (response.status === 403) {
    window.location.href = "/login";
    return fallbackMessage;
  }

  try {
    const errorText = await response.text();
    return errorText || fallbackMessage;
  } catch (error) {
    return fallbackMessage;
  }
}
