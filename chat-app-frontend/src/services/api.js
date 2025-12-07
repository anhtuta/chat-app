/**
 * API service for communicating with Spring Boot backend
 */

const API_BASE_URL = process.env.REACT_APP_API_URL || "";

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
  throw new Error("Failed to load groups");
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
  throw new Error("Failed to load public messages");
}

/**
 * Get group messages
 */
export async function getGroupMessages(groupId) {
  const response = await fetch(`${API_BASE_URL}/api/messages/groups/${groupId}`, {
    credentials: "include",
  });
  if (response.ok) {
    return response.json();
  }
  throw new Error("Failed to load group messages");
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
  throw new Error("Failed to load users");
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
