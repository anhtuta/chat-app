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
  subscribePersonal: () => () => { },
  sendMessage: () => false,
});

/**
 * Holds a single STOMP/SockJS connection for the whole app and
 * automatically re-subscribes registered topics after reconnects.
 */
export function WebSocketProvider({ children }) {
  const [isConnected, setIsConnected] = useState(false);

  // Store single chat-topic subscription: { topic, callback, subscription }
  const subscriptionRef = useRef(null);

  // Store persistent personal-queue subscriptions: Map<topic, { callback, subscription }>
  // These survive chat-switching (e.g. /user/queue/group-updates).
  const personalSubscriptionsRef = useRef(new Map());

  const internalSubscribe = () => {
    if (!subscriptionRef.current?.callback) return null;

    const { topic, callback } = subscriptionRef.current;
    const subscription = subscribeToTopic(topic, (message) => {
      // Use latest callback reference
      if (subscriptionRef.current?.callback) {
        subscriptionRef.current.callback(message);
      }
    });

    if (subscription) {
      subscriptionRef.current.subscription = subscription;
    }

    return subscription;
  };

  const subscribe = (topic, callback) => {
    // Unsubscribe from any existing subscription
    if (subscriptionRef.current?.subscription) {
      subscriptionRef.current.subscription.unsubscribe();
    }

    subscriptionRef.current = { topic, callback, subscription: null };

    if (isConnected) {
      internalSubscribe();
    }

    // Return an unsubscribe function to allow callers to remove interest
    return () => unsubscribe();
  };

  const unsubscribe = () => {
    if (subscriptionRef.current?.subscription) {
      unsubscribeSubscription(subscriptionRef.current.subscription);
    }
    subscriptionRef.current = null;
  };

  /**
   * Subscribe to a personal (user-specific) topic that survives chat switching.
   * Intended for topics like /user/queue/group-updates.
   * Returns an unsubscribe function.
   */
  const subscribePersonal = (topic, callback) => {
    // Tear down any previous subscription on the same topic
    const existing = personalSubscriptionsRef.current.get(topic);
    if (existing?.subscription) {
      unsubscribeSubscription(existing.subscription);
    }

    const entry = { callback, subscription: null };
    personalSubscriptionsRef.current.set(topic, entry);

    if (isConnected) {
      const subscription = subscribeToTopic(topic, (message) => {
        const latest = personalSubscriptionsRef.current.get(topic);
        if (latest?.callback) latest.callback(message);
      });
      entry.subscription = subscription;
    }

    return () => {
      const current = personalSubscriptionsRef.current.get(topic);
      if (current?.subscription) {
        unsubscribeSubscription(current.subscription);
      }
      personalSubscriptionsRef.current.delete(topic);
    };
  };

  // Re-subscribe after a reconnect
  const resubscribeAll = () => {
    if (subscriptionRef.current) {
      internalSubscribe();
    }
    // Re-subscribe all personal queues after reconnect
    for (const [topic, entry] of personalSubscriptionsRef.current.entries()) {
      const subscription = subscribeToTopic(topic, (message) => {
        const latest = personalSubscriptionsRef.current.get(topic);
        if (latest?.callback) latest.callback(message);
      });
      entry.subscription = subscription;
    }
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
      // Clean up chat subscription
      if (subscriptionRef.current?.subscription) {
        unsubscribeSubscription(subscriptionRef.current.subscription);
      }
      subscriptionRef.current = null;
      // Clean up personal subscriptions
      for (const entry of personalSubscriptionsRef.current.values()) {
        if (entry.subscription) unsubscribeSubscription(entry.subscription);
      }
      personalSubscriptionsRef.current.clear();
      disconnectWebSocket();
    };
  }, []);

  const value = useMemo(
    () => ({
      isConnected,
      subscribe,
      unsubscribe,
      subscribePersonal,
      sendMessage: sendWebSocketMessage,
    }),
    [isConnected]
  );

  return <WebSocketContext.Provider value={value}>{children}</WebSocketContext.Provider>;
}

export function useWebSocket() {
  return useContext(WebSocketContext);
}
