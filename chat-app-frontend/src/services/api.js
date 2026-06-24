/**
 * API service for communicating with Spring Boot backend
 * In development: requests are proxied via "proxy" in package.json
 * In production: requests go directly to the backend
 */

const API_BASE_URL = "";

/**
 * Check if user is authenticated
 */
export async function checkAuth() {
  const response = await fetch(`${API_BASE_URL}/api/auth/check`, {
    credentials: "include",
  });
  return response.json();
}

/**
 * Login
 */
export async function login(username, password) {
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
export async function register(username, password) {
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
export async function logout() {
  const response = await fetch(`${API_BASE_URL}/api/auth/logout`, {
    method: "POST",
    credentials: "include",
  });
  return response;
}

/**
 * Get all groups for the current user
 */
export async function getGroups() {
  const response = await fetch(`${API_BASE_URL}/api/groups`, {
    credentials: "include",
  });
  if (response.ok) {
    return response.json();
  }
  handleErrorResponse(response);
}

/**
 * Get public chat messages
 */
export async function getPublicMessages() {
  const response = await fetch(`${API_BASE_URL}/api/messages/public`, {
    credentials: "include",
  });
  if (response.ok) {
    return response.json();
  }
  handleErrorResponse(response);
}

/**
 * Get group messages
 */
export async function getGroupMessages(groupId, { beforeTimestamp, beforeId, size = 10 } = {}) {
  const queryParams = new URLSearchParams({ size: String(size) });

  if (beforeTimestamp) {
    queryParams.set("beforeTimestamp", beforeTimestamp);
  }

  if (beforeId !== undefined && beforeId !== null) {
    queryParams.set("beforeId", String(beforeId));
  }

  const response = await fetch(`${API_BASE_URL}/api/messages/groups/${groupId}?${queryParams.toString()}`, {
    credentials: "include",
  });
  if (response.ok) {
    return response.json();
  }
  handleErrorResponse(response);
}

/**
 * Get all users (for creating groups)
 */
export async function getUsers() {
  const response = await fetch(`${API_BASE_URL}/api/groups/users`, {
    credentials: "include",
  });
  if (response.ok) {
    return response.json();
  }
  handleErrorResponse(response);
}

/**
 * Create a new group
 */
export async function createGroup(name, participantIds) {
  const response = await fetch(`${API_BASE_URL}/api/groups`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
    },
    credentials: "include",
    body: JSON.stringify({
      name,
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
export async function markGroupAsRead(groupId, lastReadMessageId) {
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
  handleErrorResponse(response);
}

/**
 * Get aggregated unread count across all groups for current user.
 */
export async function getTotalUnreadCount() {
  const response = await fetch(`${API_BASE_URL}/api/groups/unread/total`, {
    credentials: "include",
  });
  if (response.ok) {
    return response.json();
  }
  handleErrorResponse(response);
}

/**
 * Prepare a media message upload session.
 */
export async function prepareMediaMessage({ chatScope, groupId, messageType, attachments }) {
  const response = await fetch(`${API_BASE_URL}/api/media/messages/prepare`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
    },
    credentials: "include",
    body: JSON.stringify({
      chatScope,
      groupId,
      messageType,
      attachments,
    }),
  });

  if (response.ok) {
    return response.json();
  }

  throw new Error(await readErrorMessage(response, "Failed to prepare media message"));
}

/**
 * Complete a prepared media upload session and publish the final message.
 */
export async function completeMediaMessage(uploadSessionId, attachments) {
  const response = await fetch(`${API_BASE_URL}/api/media/messages/upload-sessions/${uploadSessionId}/complete`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
    },
    credentials: "include",
    body: JSON.stringify({ attachments }),
  });

  if (response.ok) {
    return response.json();
  }

  throw new Error(await readErrorMessage(response, "Failed to complete media message"));
}

/**
 * Upload a file directly to a storage-provider presigned URL.
 */
export function uploadFileToPresignedUrl(url, file, { onProgress } = {}) {
  const xhr = new XMLHttpRequest();

  const promise = new Promise((resolve, reject) => {
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

function handleErrorResponse(response) {
  if (response.status === 403) {
    // redirect to login
    window.location.href = "/login";
  } else {
    throw new Error(`API error: ${response.status} ${response.statusText}`);
  }
}

async function readErrorMessage(response, fallbackMessage) {
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
