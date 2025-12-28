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

  // Store single subscription: { topic, callback, subscription }
  const subscriptionRef = useRef(null);

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

  // Re-subscribe after a reconnect
  const resubscribeAll = () => {
    if (subscriptionRef.current) {
      internalSubscribe();
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
      // Clean up subscription and disconnect
      if (subscriptionRef.current?.subscription) {
        unsubscribeSubscription(subscriptionRef.current.subscription);
      }
      subscriptionRef.current = null;
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
