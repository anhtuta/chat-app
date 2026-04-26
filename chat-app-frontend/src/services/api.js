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
export async function getGroupMessages(groupId, page = 0, size = 10) {
  const response = await fetch(`${API_BASE_URL}/api/messages/groups/${groupId}?page=${page}&size=${size}`, {
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

function handleErrorResponse(response) {
  if (response.status === 403) {
    // redirect to login
    window.location.href = "/login";
  } else {
    throw new Error(`API error: ${response.status} ${response.statusText}`);
  }
}
