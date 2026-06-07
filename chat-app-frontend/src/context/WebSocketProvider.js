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
  subscribeSingleGroup: () => () => { },
  unsubscribeSingleGroup: () => { },
  subscribeGroupUpdates: () => () => { },
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

  // Store persistent personal-queue subscriptions:
  // Map<topic, { callback, subscription, refCount, cleanupTimer }>
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
      console.log("WebSocketProvider.internalSubscribe - subscribed to", topic, "id:", subscription?.id);
    }

    return subscription;
  };
  const subscribeSingleGroup = React.useCallback((topic, callback) => {
    // Unsubscribe from any existing subscription
    if (subscriptionRef.current?.subscription) {
      console.log("WebSocketProvider.subscribeSingleGroup - tearing down existing chat subscription", subscriptionRef.current.topic, "id:", subscriptionRef.current.subscription?.id);
      unsubscribeSubscription(subscriptionRef.current.subscription);
    }

    subscriptionRef.current = { topic, callback, subscription: null };

    if (isConnected) {
      internalSubscribe();
    }

    // Return an unsubscribe function to allow callers to remove interest
    return () => unsubscribeSingleGroup();
  }, [isConnected]);

  const unsubscribeSingleGroup = React.useCallback(() => {
    if (subscriptionRef.current?.subscription) {
      console.log("WebSocketProvider.unsubscribeSingleGroup - unsubscribing chat topic", subscriptionRef.current.topic, "id:", subscriptionRef.current.subscription?.id);
      unsubscribeSubscription(subscriptionRef.current.subscription);
    }
    subscriptionRef.current = null;
  }, []);

  /**
   * Subscribe to a personal (user-specific) topic that survives chat switching.
   * Intended for topics like /user/queue/group-updates.
   * Returns an unsubscribe function.
   */
  const subscribeGroupUpdates = React.useCallback((topic, callback) => {
    const existing = personalSubscriptionsRef.current.get(topic);
    if (existing) {
      // Reuse existing STOMP subscription for this topic.
      existing.callback = callback;
      existing.refCount = Number(existing.refCount || 0) + 1;
      if (existing.cleanupTimer) {
        clearTimeout(existing.cleanupTimer);
        existing.cleanupTimer = null;
      }
      console.log("WebSocketProvider.subscribeGroupUpdates - reusing topic", topic, "id:", existing.subscription?.id, "refCount:", existing.refCount);

      let released = false;
      return () => {
        if (released) return;
        released = true;

        const current = personalSubscriptionsRef.current.get(topic);
        if (!current) return;

        current.refCount = Math.max(0, Number(current.refCount || 0) - 1);
        console.log("WebSocketProvider.subscribeGroupUpdates - release", topic, "id:", current.subscription?.id, "refCount:", current.refCount);

        if (current.refCount > 0) return;

        // Delay cleanup briefly so rapid effect cleanup/re-subscribe cycles
        // during route transitions do not flap STOMP subscriptions.
        if (current.cleanupTimer) {
          clearTimeout(current.cleanupTimer);
        }
        current.cleanupTimer = setTimeout(() => {
          const latest = personalSubscriptionsRef.current.get(topic);
          if (!latest || Number(latest.refCount || 0) > 0) return;

          if (latest.subscription) {
            console.log("WebSocketProvider.subscribeGroupUpdates - unsubscribing topic", topic, "id:", latest.subscription?.id);
            unsubscribeSubscription(latest.subscription);
          }
          personalSubscriptionsRef.current.delete(topic);
        }, 250);
      };
    }

    const entry = { callback, subscription: null, refCount: 1, cleanupTimer: null };
    personalSubscriptionsRef.current.set(topic, entry);

    if (isConnected) {
      const subscription = subscribeToTopic(topic, (message) => {
        const latest = personalSubscriptionsRef.current.get(topic);
        if (latest?.callback) latest.callback(message);
      });
      entry.subscription = subscription;
      console.log("WebSocketProvider.subscribeGroupUpdates - subscribed to", topic, "id:", subscription?.id);
    }

    let released = false;
    return () => {
      if (released) return;
      released = true;

      const current = personalSubscriptionsRef.current.get(topic);
      if (!current) return;

      current.refCount = Math.max(0, Number(current.refCount || 0) - 1);
      console.log("WebSocketProvider.subscribeGroupUpdates - release", topic, "id:", current.subscription?.id, "refCount:", current.refCount);

      if (current.refCount > 0) return;

      if (current.cleanupTimer) {
        clearTimeout(current.cleanupTimer);
      }
      current.cleanupTimer = setTimeout(() => {
        const latest = personalSubscriptionsRef.current.get(topic);
        if (!latest || Number(latest.refCount || 0) > 0) return;

        if (latest.subscription) {
          console.log("WebSocketProvider.subscribeGroupUpdates - unsubscribing topic", topic, "id:", latest.subscription?.id);
          unsubscribeSubscription(latest.subscription);
        }
        personalSubscriptionsRef.current.delete(topic);
      }, 250);
    };
  }, [isConnected]);

  // Re-subscribe after a reconnect
  const resubscribeAll = () => {
    if (subscriptionRef.current) {
      internalSubscribe();
    }
    // Re-subscribe all personal queues after reconnect
    for (const [topic, entry] of personalSubscriptionsRef.current.entries()) {
      if (entry.cleanupTimer) {
        clearTimeout(entry.cleanupTimer);
        entry.cleanupTimer = null;
      }
      console.log("WebSocketProvider.resubscribeAll - re-subscribing personal topic", topic);
      const subscription = subscribeToTopic(topic, (message) => {
        const latest = personalSubscriptionsRef.current.get(topic);
        if (latest?.callback) latest.callback(message);
      });
      entry.subscription = subscription;
      console.log("WebSocketProvider.resubscribeAll - subscribed", topic, "id:", subscription?.id);
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
        console.log("WebSocketProvider.cleanup - unsubscribing chat subscription", subscriptionRef.current.topic, "id:", subscriptionRef.current.subscription?.id);
        unsubscribeSubscription(subscriptionRef.current.subscription);
      }
      subscriptionRef.current = null;
      // Clean up personal subscriptions
      for (const [topic, entry] of personalSubscriptionsRef.current.entries()) {
        if (entry.cleanupTimer) {
          clearTimeout(entry.cleanupTimer);
          entry.cleanupTimer = null;
        }
        if (entry.subscription) {
          console.log("WebSocketProvider.cleanup - unsubscribing personal subscription", topic, "id:", entry.subscription?.id);
          unsubscribeSubscription(entry.subscription);
        }
      }
      personalSubscriptionsRef.current.clear();
      disconnectWebSocket();
    };
  }, []);

  const value = useMemo(
    () => ({
      isConnected,
      subscribeSingleGroup,
      unsubscribeSingleGroup,
      subscribeGroupUpdates,
      sendMessage: sendWebSocketMessage,
    }),
    [isConnected, subscribeSingleGroup, unsubscribeSingleGroup, subscribeGroupUpdates]
  );

  return <WebSocketContext.Provider value={value}>{children}</WebSocketContext.Provider>;
}

export function useWebSocket() {
  return useContext(WebSocketContext);
}
