/**
 * WebSocket service using STOMP over SockJS
 * In development: requests are proxied via "proxy" in package.json
 * In production: requests go directly to the backend
 */
import { Client } from "@stomp/stompjs";
import SockJS from "sockjs-client";

const WS_BASE_URL = "";

let stompClient = null;

/**
 * Create and connect WebSocket client
 */
export function connectWebSocket(onConnect, onError) {
  if (stompClient && stompClient.connected) {
    return stompClient;
  }

  const wsUrl = `${WS_BASE_URL}/ws`;
  stompClient = new Client({
    webSocketFactory: () => new SockJS(wsUrl),
    debug: (str) => {
      console.log("STOMP:", str);
    },
    reconnectDelay: 5000,
    heartbeatIncoming: 4000,
    heartbeatOutgoing: 4000,
    onConnect: (frame) => {
      console.log("Connected: " + frame);
      if (onConnect) onConnect(frame);
    },
    onStompError: (frame) => {
      console.error("STOMP error:", frame);
      if (onError) onError(frame);
    },
    onWebSocketError: (event) => {
      console.error("WebSocket error:", event);
      if (onError) onError(event);
    },
  });

  stompClient.activate();
  return stompClient;
}

/**
 * Disconnect WebSocket
 */
export function disconnectWebSocket() {
  if (stompClient) {
    stompClient.deactivate();
    stompClient = null;
  }
}

/**
 * Subscribe to a topic
 */
export function subscribeToTopic(topic, callback) {
  if (!stompClient || !stompClient.connected) {
    console.error("STOMP client not connected");
    return null;
  }

  return stompClient.subscribe(topic, (message) => {
    try {
      const data = JSON.parse(message.body);
      callback(data);
    } catch (error) {
      console.error("Error parsing message:", error);
    }
  });
}

/**
 * Send message to a destination
 */
export function sendMessage(destination, message) {
  if (!stompClient || !stompClient.connected) {
    console.error("STOMP client not connected");
    return false;
  }

  stompClient.publish({
    destination,
    body: JSON.stringify(message),
  });
  return true;
}

/**
 * Get the STOMP client instance
 */
export function getStompClient() {
  return stompClient;
}
