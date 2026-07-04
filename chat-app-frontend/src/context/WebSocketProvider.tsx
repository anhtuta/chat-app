import React, { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from "react";
import {
  connectWebSocket,
  disconnectWebSocket,
  subscribeToTopic,
  unsubscribeSubscription,
  sendMessage as sendWebSocketMessage,
} from "../services/websocket";
import type { ChatMessage } from "../types/chat";
import type { GroupSummaryUpdate } from "../types/groups";
import type {
  GroupSubscription,
  TopicCallback,
  Unsubscribe,
  UserGroupUpdatesSubscription,
  WebSocketContextValue,
} from "../types/websocket";

const defaultContextValue: WebSocketContextValue = {
  isConnected: false,
  subscribeSingleGroup: () => () => {},
  unsubscribeSingleGroup: () => {},
  setGroupUpdatesHandler: () => {},
  sendMessage: () => false,
};

const WebSocketContext = createContext<WebSocketContextValue>(defaultContextValue);

interface WebSocketProviderProps {
  children: React.ReactNode;
  /** Authenticated username; when set, the provider keeps one persistent group-updates subscription. */
  username: string | null;
}

function buildUserGroupUpdatesTopic(username: string): string {
  return `/topic/user.${username}.group-updates`;
}

/**
 * Holds a single STOMP/SockJS connection for the whole app.
 *
 * Subscription model:
 * 1. Group topic (`/topic/public` or `/topic/group.{id}`) — changes when the user switches chat.
 * 2. User group-updates (`/topic/user.{username}.group-updates`) — created once per login
 *    session and torn down on logout or provider unmount.
 */
export function WebSocketProvider({ children, username }: WebSocketProviderProps) {
  const [isConnected, setIsConnected] = useState(false);

  const groupSubscriptionRef = useRef<GroupSubscription | null>(null);
  const userGroupUpdatesSubscriptionRef = useRef<UserGroupUpdatesSubscription | null>(null);

  const subscribeGroupTopic = () => {
    if (!groupSubscriptionRef.current?.callback) return null;

    const { topic } = groupSubscriptionRef.current;
    const subscription = subscribeToTopic<ChatMessage>(topic, (message) => {
      if (groupSubscriptionRef.current?.callback) {
        groupSubscriptionRef.current.callback(message);
      }
    });

    if (subscription) {
      groupSubscriptionRef.current.subscription = subscription;
      console.log("WebSocketProvider.subscribeGroupTopic - subscribed to", topic, "id:", subscription.id);
    }

    return subscription;
  };

  const subscribeUserGroupUpdates = (activeUsername: string) => {
    const topic = buildUserGroupUpdatesTopic(activeUsername);
    const callback = userGroupUpdatesSubscriptionRef.current?.callback ?? null;
    const subscription = subscribeToTopic<GroupSummaryUpdate>(topic, (update) => {
      userGroupUpdatesSubscriptionRef.current?.callback?.(update);
    });

    userGroupUpdatesSubscriptionRef.current = { username: activeUsername, callback, subscription };
    console.log("WebSocketProvider.subscribeUserGroupUpdates - subscribed to", topic, "id:", subscription?.id);
  };

  const unsubscribeUserGroupUpdates = () => {
    if (userGroupUpdatesSubscriptionRef.current?.subscription) {
      console.log("WebSocketProvider.unsubscribeUserGroupUpdates - id:", userGroupUpdatesSubscriptionRef.current.subscription.id);
      unsubscribeSubscription(userGroupUpdatesSubscriptionRef.current.subscription);
    }
    userGroupUpdatesSubscriptionRef.current = null;
  };

  const resubscribeAll = () => {
    if (groupSubscriptionRef.current) {
      subscribeGroupTopic();
    }

    const activeUsername = userGroupUpdatesSubscriptionRef.current?.username;
    if (!activeUsername) return;

    if (userGroupUpdatesSubscriptionRef.current?.subscription) {
      unsubscribeSubscription(userGroupUpdatesSubscriptionRef.current.subscription);
      userGroupUpdatesSubscriptionRef.current.subscription = null;
    }

    subscribeUserGroupUpdates(activeUsername);
  };

  const unsubscribeSingleGroup = useCallback(() => {
    if (groupSubscriptionRef.current?.subscription) {
      console.log(
        "WebSocketProvider.unsubscribeSingleGroup - unsubscribing group topic",
        groupSubscriptionRef.current.topic,
        "id:",
        groupSubscriptionRef.current.subscription.id,
      );
      unsubscribeSubscription(groupSubscriptionRef.current.subscription);
    }
    groupSubscriptionRef.current = null;
  }, []);

  const subscribeSingleGroup = useCallback(
    (topic: string, callback: TopicCallback<ChatMessage>): Unsubscribe => {
      if (groupSubscriptionRef.current?.subscription) {
        console.log(
          "WebSocketProvider.subscribeSingleGroup - tearing down existing group subscription",
          groupSubscriptionRef.current.topic,
          "id:",
          groupSubscriptionRef.current.subscription.id,
        );
        unsubscribeSubscription(groupSubscriptionRef.current.subscription);
      }

      groupSubscriptionRef.current = { topic, callback, subscription: null };

      if (isConnected) {
        subscribeGroupTopic();
      }

      return () => unsubscribeSingleGroup();
    },
    [isConnected, unsubscribeSingleGroup],
  );

  const setGroupUpdatesHandler = useCallback((callback: TopicCallback<GroupSummaryUpdate> | null) => {
    if (!userGroupUpdatesSubscriptionRef.current) {
      return;
    }
    userGroupUpdatesSubscriptionRef.current.callback = callback;
  }, []);

  // Persistent user group-updates subscription: tied to login session, not chat switching.
  useEffect(() => {
    if (!username) {
      unsubscribeUserGroupUpdates();
      return;
    }

    const current = userGroupUpdatesSubscriptionRef.current;
    if (current?.username === username && current.subscription) {
      return;
    }

    const callback = current?.callback ?? null;

    if (current?.subscription) {
      unsubscribeSubscription(current.subscription);
    }

    userGroupUpdatesSubscriptionRef.current = { username, callback, subscription: null };

    if (!isConnected) {
      return;
    }

    subscribeUserGroupUpdates(username);
  }, [username, isConnected]);

  useEffect(() => {
    connectWebSocket(
      () => {
        setIsConnected(true);
        resubscribeAll();
      },
      () => {
        setIsConnected(false);
      },
      () => {
        setIsConnected(false);
      },
    );

    return () => {
      setIsConnected(false);

      if (groupSubscriptionRef.current?.subscription) {
        console.log(
          "WebSocketProvider.cleanup - unsubscribing group subscription",
          groupSubscriptionRef.current.topic,
          "id:",
          groupSubscriptionRef.current.subscription.id,
        );
        unsubscribeSubscription(groupSubscriptionRef.current.subscription);
      }
      groupSubscriptionRef.current = null;

      unsubscribeUserGroupUpdates();
      disconnectWebSocket();
    };
  }, []);

  const value = useMemo<WebSocketContextValue>(
    () => ({
      isConnected,
      subscribeSingleGroup,
      unsubscribeSingleGroup,
      setGroupUpdatesHandler,
      sendMessage: sendWebSocketMessage,
    }),
    [isConnected, subscribeSingleGroup, unsubscribeSingleGroup, setGroupUpdatesHandler],
  );

  return <WebSocketContext.Provider value={value}>{children}</WebSocketContext.Provider>;
}

export function useWebSocket(): WebSocketContextValue {
  return useContext(WebSocketContext);
}
