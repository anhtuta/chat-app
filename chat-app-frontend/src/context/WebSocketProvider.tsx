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
  ChatTopicSubscriptionEntry,
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
 * 1. Chat topic (`/topic/public` or `/topic/group.{id}`) — changes when the user switches chat.
 * 2. User group-updates (`/topic/user.{username}.group-updates`) — created once per login
 *    session and torn down on logout or provider unmount.
 */
export function WebSocketProvider({ children, username }: WebSocketProviderProps) {
  const [isConnected, setIsConnected] = useState(false);

  const chatSubscriptionRef = useRef<ChatTopicSubscriptionEntry | null>(null);
  const userGroupUpdatesRef = useRef<UserGroupUpdatesSubscription | null>(null);
  const groupUpdatesHandlerRef = useRef<TopicCallback<GroupSummaryUpdate> | null>(null);

  const subscribeChatTopic = () => {
    if (!chatSubscriptionRef.current?.callback) return null;

    const { topic } = chatSubscriptionRef.current;
    const subscription = subscribeToTopic<ChatMessage>(topic, (message) => {
      if (chatSubscriptionRef.current?.callback) {
        chatSubscriptionRef.current.callback(message);
      }
    });

    if (subscription) {
      chatSubscriptionRef.current.subscription = subscription;
      console.log("WebSocketProvider.subscribeChatTopic - subscribed to", topic, "id:", subscription.id);
    }

    return subscription;
  };

  const subscribeUserGroupUpdates = (activeUsername: string) => {
    const topic = buildUserGroupUpdatesTopic(activeUsername);
    const subscription = subscribeToTopic<GroupSummaryUpdate>(topic, (update) => {
      groupUpdatesHandlerRef.current?.(update);
    });

    userGroupUpdatesRef.current = { username: activeUsername, subscription };
    console.log("WebSocketProvider.subscribeUserGroupUpdates - subscribed to", topic, "id:", subscription?.id);
  };

  const unsubscribeUserGroupUpdates = () => {
    if (userGroupUpdatesRef.current?.subscription) {
      console.log("WebSocketProvider.unsubscribeUserGroupUpdates - id:", userGroupUpdatesRef.current.subscription.id);
      unsubscribeSubscription(userGroupUpdatesRef.current.subscription);
    }
    userGroupUpdatesRef.current = null;
  };

  const resubscribeAll = () => {
    if (chatSubscriptionRef.current) {
      subscribeChatTopic();
    }

    const activeUsername = userGroupUpdatesRef.current?.username;
    if (!activeUsername) return;

    if (userGroupUpdatesRef.current?.subscription) {
      unsubscribeSubscription(userGroupUpdatesRef.current.subscription);
      userGroupUpdatesRef.current.subscription = null;
    }

    subscribeUserGroupUpdates(activeUsername);
  };

  const unsubscribeSingleGroup = useCallback(() => {
    if (chatSubscriptionRef.current?.subscription) {
      console.log(
        "WebSocketProvider.unsubscribeSingleGroup - unsubscribing chat topic",
        chatSubscriptionRef.current.topic,
        "id:",
        chatSubscriptionRef.current.subscription.id,
      );
      unsubscribeSubscription(chatSubscriptionRef.current.subscription);
    }
    chatSubscriptionRef.current = null;
  }, []);

  const subscribeSingleGroup = useCallback(
    (topic: string, callback: TopicCallback<ChatMessage>): Unsubscribe => {
      if (chatSubscriptionRef.current?.subscription) {
        console.log(
          "WebSocketProvider.subscribeSingleGroup - tearing down existing chat subscription",
          chatSubscriptionRef.current.topic,
          "id:",
          chatSubscriptionRef.current.subscription.id,
        );
        unsubscribeSubscription(chatSubscriptionRef.current.subscription);
      }

      chatSubscriptionRef.current = { topic, callback, subscription: null };

      if (isConnected) {
        subscribeChatTopic();
      }

      return () => unsubscribeSingleGroup();
    },
    [isConnected, unsubscribeSingleGroup],
  );

  const setGroupUpdatesHandler = useCallback((callback: TopicCallback<GroupSummaryUpdate> | null) => {
    groupUpdatesHandlerRef.current = callback;
  }, []);

  // Persistent user group-updates subscription: tied to login session, not chat switching.
  useEffect(() => {
    if (!username) {
      unsubscribeUserGroupUpdates();
      return;
    }

    const current = userGroupUpdatesRef.current;
    if (current?.username === username && current.subscription) {
      return;
    }

    if (current?.subscription) {
      unsubscribeSubscription(current.subscription);
    }

    userGroupUpdatesRef.current = { username, subscription: null };

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

      if (chatSubscriptionRef.current?.subscription) {
        console.log(
          "WebSocketProvider.cleanup - unsubscribing chat subscription",
          chatSubscriptionRef.current.topic,
          "id:",
          chatSubscriptionRef.current.subscription.id,
        );
        unsubscribeSubscription(chatSubscriptionRef.current.subscription);
      }
      chatSubscriptionRef.current = null;

      unsubscribeUserGroupUpdates();
      groupUpdatesHandlerRef.current = null;
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
