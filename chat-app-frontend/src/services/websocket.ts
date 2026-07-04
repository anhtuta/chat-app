/**
 * WebSocket service using STOMP over SockJS
 * In development: requests are proxied via "proxy" in package.json
 * In production: requests go directly to the backend
 *
 * Note: Only WebSocketProvider should use this service directly. Other components
 * should use the WebSocket context via useWebSocket().
 */
import { Client, type IFrame, type StompSubscription } from "@stomp/stompjs";
import SockJS from "sockjs-client";
import type { TopicCallback, WebSocketOutboundMessage } from "../types/websocket";

const WS_BASE_URL = "";

let stompClient: Client | null = null;

type WebSocketErrorHandler = (error: IFrame | Event) => void;
type WebSocketDisconnectHandler = (frame?: IFrame) => void;

/**
 * Create and connect WebSocket client
 * @param {function} onConnect - Callback when connected
 * @param {function} onError - Callback on error
 * @param {function} onDisconnect - Callback on disconnect
 * @returns {Client} STOMP client instance
 */
export function connectWebSocket(
  onConnect?: (frame: IFrame) => void,
  onError?: WebSocketErrorHandler,
  onDisconnect?: WebSocketDisconnectHandler,
): Client {
  if (stompClient?.connected) {
    console.log("WebSocket already connected, reusing existing connection");
    return stompClient;
  }

  // Always use absolute path from root, not relative to current route
  const wsUrl = `${WS_BASE_URL}/ws`;
  console.log("Connecting to WebSocket:", wsUrl);

  stompClient = new Client({
    webSocketFactory: () => {
      // SockJS needs HTTP/HTTPS URLs, not WebSocket URLs
      // It will automatically upgrade to WebSocket if available
      let sockJsUrl = wsUrl;

      if (!wsUrl.startsWith("http://") && !wsUrl.startsWith("https://")) {
        // Convert relative path to absolute HTTP URL
        sockJsUrl = `${window.location.protocol}//${window.location.host}${wsUrl}`;
      }

      console.log("Using SockJS URL:", sockJsUrl);
      return new SockJS(sockJsUrl);
    },
    debug: (str) => {
      console.log("STOMP:", str);
    },
    reconnectDelay: 5000,
    heartbeatIncoming: 4000,
    heartbeatOutgoing: 4000,
    onConnect: (frame) => {
      console.log("✅ WebSocket Connected:", frame);
      if (onConnect) onConnect(frame);
    },
    onStompError: (frame) => {
      console.error("❌ STOMP error:", frame);
      if (onError) onError(frame);
    },
    onWebSocketError: (event) => {
      console.error("❌ WebSocket error:", event);
      if (onError) onError(event);
    },
    onDisconnect: (frame) => {
      console.log("WebSocket disconnected", frame || "");
      if (onDisconnect) onDisconnect(frame);
    },
  });

  console.log("Activating STOMP client...");
  stompClient.activate();
  return stompClient;
}

/**
 * Disconnect WebSocket
 */
export function disconnectWebSocket(): void {
  console.log("Disconnecting WebSocket...");
  if (stompClient) {
    stompClient.deactivate();
    stompClient = null;
  }
}

/**
 * Subscribe to a topic
 */
export function subscribeToTopic<TPayload>(
  topic: string,
  callback: TopicCallback<TPayload>,
): StompSubscription | null {
  if (!stompClient?.connected) {
    console.error("STOMP client not connected");
    return null;
  }

  console.log("Subscribing to topic:", topic);
  return stompClient.subscribe(topic, (message) => {
    try {
      console.log("Received message on topic:", message, topic);
      const data = JSON.parse(message.body) as TPayload;
      callback(data);
    } catch (error) {
      console.error("Error parsing message:", error);
    }
  });
}

/**
 * Unsubscribe a previously created subscription
 * Note: when unsubscribe, we only know the subscription id, not the topic/destination (so we cannot use: subscription?.destination)
 */
export function unsubscribeSubscription(subscription: StompSubscription | null | undefined): void {
  if (!subscription) return;
  try {
    console.log("Unsubscribing subscription id:", subscription?.id);
    subscription.unsubscribe();
  } catch (error) {
    console.warn("Failed to unsubscribe subscription:", error);
  }
}

/**
 * Send message to a destination
 */
export function sendMessage(destination: string, message: WebSocketOutboundMessage): boolean {
  console.log("Attempting to send message:", { destination, connected: stompClient?.connected });

  if (!stompClient?.connected) {
    console.error(
      "❌ STOMP client not connected. Current state:",
      stompClient?.active ? "active but not connected" : "not active",
    );
    return false;
  }

  console.log("✅ Sending message to", destination);
  stompClient.publish({
    destination,
    body: JSON.stringify(message),
  });
  return true;
}

/**
 * Get the STOMP client instance
 */
export function getStompClient(): Client | null {
  return stompClient;
}
