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
} from "../types/chat";
import type { ChatGroup, GroupMemberPage, SelectableUser } from "../types/groups";

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
 * Upload a file directly to a storage-provider presigned URL.
 */
export function uploadFileToPresignedUrl(
  url: string,
  file: File,
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
    if (file.type) {
      xhr.setRequestHeader("Content-Type", file.type);
    }
    xhr.send(file);
  });

  return {
    promise,
    abort: () => xhr.abort(),
  };
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
