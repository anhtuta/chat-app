import React, { createContext, useContext, useEffect, useMemo, useRef, useState } from "react";
import {
  connectWebSocket,
  disconnectWebSocket,
  subscribeToTopic,
  unsubscribeSubscription,
  sendMessage as sendWebSocketMessage,
} from "../services/websocket";

const WebSocketContext = createContext({
  isConnected: false,
  subscribe: () => () => { },
  unsubscribe: () => { },
  sendMessage: () => false,
});

/**
 * Holds a single STOMP/SockJS connection for the whole app and
 * automatically re-subscribes registered topics after reconnects.
 */
export function WebSocketProvider({ children }) {
  const [isConnected, setIsConnected] = useState(false);

  // Map topic -> { callback, subscription }
  const subscriptionsRef = useRef(new Map());

  const internalSubscribe = (topic) => {
    const entry = subscriptionsRef.current.get(topic);
    if (!entry?.callback) return null;

    const subscription = subscribeToTopic(topic, (message) => {
      // Use latest callback reference stored in map
      const currentEntry = subscriptionsRef.current.get(topic);
      if (currentEntry?.callback) {
        currentEntry.callback(message);
      }
    });

    if (subscription) {
      subscriptionsRef.current.set(topic, { ...entry, subscription });
    }

    return subscription;
  };

  const subscribe = (topic, callback) => {
    // Replace any existing callback/subscription for this topic
    const existing = subscriptionsRef.current.get(topic);
    if (existing?.subscription) {
      existing.subscription.unsubscribe();
    }

    subscriptionsRef.current.set(topic, { callback, subscription: null });

    if (isConnected) {
      internalSubscribe(topic);
    }

    // Return an unsubscribe function to allow callers to remove interest
    return () => unsubscribe(topic);
  };

  const unsubscribe = (topic) => {
    const entry = subscriptionsRef.current.get(topic);
    if (entry?.subscription) {
      unsubscribeSubscription(entry.subscription);
    }
    subscriptionsRef.current.delete(topic);
  };

  // Re-subscribe all registered topics after a reconnect
  const resubscribeAll = () => {
    subscriptionsRef.current.forEach((_, topic) => {
      internalSubscribe(topic);
    });
  };

  useEffect(() => {
    const client = connectWebSocket(
      () => {
        setIsConnected(true);
        resubscribeAll();
      },
      () => {
        setIsConnected(false);
      },
      () => {
        setIsConnected(false);
      }
    );

    return () => {
      setIsConnected(false);
      // Clean up all subscriptions and disconnect
      subscriptionsRef.current.forEach((entry) => {
        if (entry.subscription) unsubscribeSubscription(entry.subscription);
      });
      subscriptionsRef.current.clear();
      disconnectWebSocket();
    };
  }, []);

  const value = useMemo(
    () => ({
      isConnected,
      subscribe,
      unsubscribe,
      sendMessage: sendWebSocketMessage,
    }),
    [isConnected]
  );

  return <WebSocketContext.Provider value={value}>{children}</WebSocketContext.Provider>;
}

export function useWebSocket() {
  return useContext(WebSocketContext);
}
