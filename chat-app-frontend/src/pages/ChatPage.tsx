import React, { useState, useEffect, useRef } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { Box } from "@mui/material";
import Sidebar from "../components/Sidebar";
import ChatArea from "../components/ChatArea";
import CreateGroupModal from "../components/CreateGroupModal";
import { getGroups, getPublicMessages, getGroupMessages, markGroupAsRead } from "../services/api";
import { useWebSocket } from "../context/WebSocketProvider";
import type { ChatMessage } from "../types/chat";
import type { ChatGroup } from "../types/groups";
import type { ThemeId, ThemeOption } from "../types/theme";
import type { Unsubscribe } from "../types/websocket";

type ChatRouteId = "public" | number;

interface GroupMessageCursor {
  timestamp: string;
  id: number;
}

interface ChatPageProps {
  username: string | null;
  onLogout: () => void | Promise<void>;
  selectedThemeId: ThemeId;
  onThemeChange: (themeId: ThemeId) => void;
  themeOptions: ThemeOption[];
}

function ChatPage({
  username,
  onLogout,
  selectedThemeId,
  onThemeChange,
  themeOptions,
}: ChatPageProps) {
  const GROUP_PAGE_SIZE = 10;

  const toEpochMillis = (value: string | null | undefined): number => {
    if (!value) {
      return 0;
    }
    const parsed = Date.parse(value);
    return Number.isNaN(parsed) ? 0 : parsed;
  };

  const navigate = useNavigate();
  const { groupId } = useParams<{ groupId?: string }>();

  const [groups, setGroups] = useState<ChatGroup[]>([]);
  const [currentChatId, setCurrentChatId] = useState<ChatRouteId>("public");
  const [currentChatName, setCurrentChatName] = useState("Public Chat");
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [isLoading, setIsLoading] = useState(false);
  const [isLoadingOlder, setIsLoadingOlder] = useState(false);
  const [hasMoreGroupMessages, setHasMoreGroupMessages] = useState(true);
  const [showCreateGroupModal, setShowCreateGroupModal] = useState(false);

  // Hold the current topic's unsubscribe function so we can cleanly unsubscribe when switching chats or unmounting.
  const currentUnsubscribeRef = useRef<Unsubscribe | null>(null);
  const oldestGroupCursorRef = useRef<GroupMessageCursor | null>(null);
  const groupsRef = useRef<ChatGroup[]>([]);
  const lastMarkedMessagePerGroupRef = useRef<Map<number, number>>(new Map());

  const currentChatIdRef = useRef<ChatRouteId>("public");
  const {
    isConnected: wsConnected,
    subscribeSingleGroup,
    setGroupUpdatesHandler,
    sendMessage: sendWebSocketMessage,
  } = useWebSocket();

  const upsertMessage = (previousMessages: ChatMessage[], incomingMessage: ChatMessage | null | undefined) => {
    if (!incomingMessage) {
      return previousMessages;
    }

    if (incomingMessage.id === undefined || incomingMessage.id === null) {
      return [...previousMessages, incomingMessage];
    }

    const existingIndex = previousMessages.findIndex((message) => message.id === incomingMessage.id);
    if (existingIndex === -1) {
      return [...previousMessages, incomingMessage];
    }

    const nextMessages = [...previousMessages];
    nextMessages[existingIndex] = incomingMessage;
    return nextMessages;
  };

  // Load groups once on mount; cleanup any active subscription on unmount
  useEffect(() => {
    loadGroups();

    return () => {
      // Cleanup current subscription on unmount
      if (currentUnsubscribeRef.current) {
        currentUnsubscribeRef.current();
      }
    };
  }, []);

  // Keep a ref copy of groups so subscription handlers can read the latest value
  useEffect(() => {
    groupsRef.current = groups;
  }, [groups]);

  // Register sidebar update handler. The STOMP subscription itself is owned by WebSocketProvider
  // for the whole login session and is not tied to chat switching.
  useEffect(() => {
    setGroupUpdatesHandler((groupSummaryUpdate) => {
      const updatedGroupId = Number(groupSummaryUpdate.groupId);
      setGroups((prev) => {
        const groupIndex = prev.findIndex((group) => Number(group.id) === updatedGroupId);
        if (groupIndex === -1) {
          return prev;
        }

        const currentGroup = prev[groupIndex];
        const incomingTimestamp = toEpochMillis(groupSummaryUpdate.latestMessageAt);
        const currentTimestamp = toEpochMillis(currentGroup.latestMessageAt);

        // Guard against out-of-order delivery: ignore stale group-summary updates.
        if (incomingTimestamp < currentTimestamp) {
          return prev;
        }

        const updatedGroup: ChatGroup = {
          ...currentGroup,
          latestMessage: groupSummaryUpdate.latestMessage,
          latestMessageSender: groupSummaryUpdate.latestMessageSender,
          latestMessageAt: groupSummaryUpdate.latestMessageAt,
          unreadCount: currentChatIdRef.current === updatedGroupId ? 0 : (Number(currentGroup.unreadCount || 0) + 1),
        };

        return [
          updatedGroup,
          ...prev.slice(0, groupIndex),
          ...prev.slice(groupIndex + 1),
        ];
      });
    });

    return () => setGroupUpdatesHandler(null);
  }, [setGroupUpdatesHandler]);

  // For group chats (not public): when messages settle, mark the group as read
  // on the server up to the latest visible message. Guards against duplicates.
  useEffect(() => {
    if (currentChatId === "public" || isLoading) {
      return;
    }

    const latestVisibleMessage = messages[messages.length - 1];
    const latestVisibleMessageId = latestVisibleMessage?.id;
    if (latestVisibleMessageId === undefined || latestVisibleMessageId === null) {
      return;
    }

    const activeGroupId = Number(currentChatId);
    const lastMarkedMessageId = lastMarkedMessagePerGroupRef.current.get(activeGroupId);
    if (lastMarkedMessageId === latestVisibleMessageId) {
      return;
    }

    markGroupAsRead(activeGroupId, latestVisibleMessageId)
      .then(() => {
        lastMarkedMessagePerGroupRef.current.set(activeGroupId, latestVisibleMessageId);
        setGroups((prev) =>
          prev.map((group) =>
            Number(group.id) === activeGroupId ? { ...group, unreadCount: 0 } : group,
          ),
        );
      })
      .catch((error) => {
        console.error("Error marking group as read:", error);
      });
  }, [currentChatId, messages, isLoading]);

  // Maintain a cursor for the oldest loaded message so "load older" can page correctly.
  useEffect(() => {
    const oldestMessage = messages[0];
    if (!oldestMessage?.timestamp || oldestMessage?.id === undefined || oldestMessage?.id === null) {
      oldestGroupCursorRef.current = null;
      return;
    }

    oldestGroupCursorRef.current = {
      timestamp: oldestMessage.timestamp,
      id: oldestMessage.id,
    };
  }, [messages]);

  useEffect(() => {
    // Sync URL `groupId` param to the selected chat. Falls back to public on missing/invalid id.
    if (!groupId || groupId === "public") {
      switchToChat("public", "Public Chat");
      return;
    }

    const numericId = Number(groupId);
    if (Number.isNaN(numericId)) {
      switchToChat("public", "Public Chat");
      return;
    }

    const group = groupsRef.current.find((g) => Number(g.id) === numericId);
    switchToChat(numericId, group?.name || `Group ${numericId}`);
    // groupsRef is stable; switchToChat is intentionally omitted to avoid re-syncing on every render.
    // eslint-disable-next-line react-hooks/exhaustive-deps -- only react to URL param changes
  }, [groupId]);

  const loadGroups = async () => {
    try {
      const groupsData = await getGroups();
      setGroups(groupsData.map((group) => ({
        ...group,
        unreadCount: Number(group.unreadCount || 0),
      })));
    } catch (error) {
      console.error("Error loading groups:", error);
    }
  };

  const totalUnreadCount = groups.reduce((total, group) => total + Number(group.unreadCount || 0), 0);

  const switchToChat = async (chatId: ChatRouteId, chatName: string) => {
    // Update ref immediately so subscriptions can use the latest value
    currentChatIdRef.current = chatId;

    // Unsubscribe from previous chat
    if (currentUnsubscribeRef.current) {
      currentUnsubscribeRef.current();
      currentUnsubscribeRef.current = null;
    }

    // Update current chat
    setCurrentChatId(chatId);
    setCurrentChatName(chatName || "Public Chat");
    setMessages([]);
    setHasMoreGroupMessages(true);
    oldestGroupCursorRef.current = null;

    // Subscribe to new topic (works for both public and groups)
    const topicPath = chatId === "public" ? "/topic/public" : `/topic/group.${chatId}`;
    const unsubscribe = subscribeSingleGroup(topicPath, (message) => {
      if (currentChatIdRef.current === chatId) {
        setMessages((prev) => upsertMessage(prev, message));
      }
    });
    currentUnsubscribeRef.current = unsubscribe;

    // Load messages
    if (chatId === 'public') {
      loadMessages();
    } else {
      loadGroupMessages(chatId, { prepend: false });
    }
  };

  const loadMessages = async () => {
    setIsLoading(true);
    try {
      const messagesData = await getPublicMessages();
      setMessages(messagesData);
    } catch (error) {
      console.error("Error loading messages:", error);
    } finally {
      setIsLoading(false);
    }
  };

  const loadGroupMessages = async (
    targetGroupId: number,
    { prepend = false, cursor = null }: { prepend?: boolean; cursor?: GroupMessageCursor | null } = {},
  ) => {
    if (prepend) {
      setIsLoadingOlder(true);
    } else {
      setIsLoading(true);
    }

    try {
      const messagesData = await getGroupMessages(targetGroupId, {
        size: GROUP_PAGE_SIZE,
        beforeTimestamp: cursor?.timestamp,
        beforeId: cursor?.id,
      });

      if (prepend) {
        setMessages((prev) => {
          const existingIds = new Set(prev.map((message) => message.id));
          const uniqueOlder = messagesData.filter((message) => !existingIds.has(message.id));
          return [...uniqueOlder, ...prev];
        });
      } else {
        setMessages(messagesData);
      }

      setHasMoreGroupMessages(messagesData.length === GROUP_PAGE_SIZE);
    } catch (error) {
      console.error("Error loading group messages:", error);
    } finally {
      if (prepend) {
        setIsLoadingOlder(false);
      } else {
        setIsLoading(false);
      }
    }
  };

  const loadOlderGroupMessages = async () => {
    if (currentChatId === "public" || isLoading || isLoadingOlder || !hasMoreGroupMessages) {
      return false;
    }

    await loadGroupMessages(currentChatId, {
      prepend: true,
      cursor: oldestGroupCursorRef.current,
    });
    return true;
  };

  const sendMessage = (content: string) => {
    if (!content.trim() || !wsConnected) {
      return;
    }

    if (currentChatId === "public") {
      sendWebSocketMessage("/app/chat.send", { content });
    } else {
      sendWebSocketMessage("/app/group.send", { content, groupId: Number(currentChatId) });
    }
  };

  const handleGroupCreated = (newGroup: ChatGroup) => {
    loadGroups();
    switchToChat(newGroup.id, newGroup.name);
  };

  const handleMediaMessageDelivered = (message: ChatMessage | null | undefined) => {
    if (!message) {
      return;
    }

    const targetChatId = message.groupId === undefined || message.groupId === null ? "public" : Number(message.groupId);
    if (currentChatIdRef.current !== targetChatId) {
      return;
    }

    setMessages((prev) => upsertMessage(prev, message));
  };

  const handleChatNavigate = (chatId: ChatRouteId) => {
    navigate(`/group/${chatId}`);
  };

  return (
    <div className="chat-page-wrapper">
      <Box sx={{ display: "flex", height: "100vh" }}>
        <Sidebar
          groups={groups}
          totalUnreadCount={totalUnreadCount}
          currentChatId={currentChatId}
          onChatSelect={handleChatNavigate}
          onCreateGroupClick={() => setShowCreateGroupModal(true)}
          selectedThemeId={selectedThemeId}
          onThemeChange={onThemeChange}
          themeOptions={themeOptions}
        />
        <ChatArea
          chatId={currentChatId}
          chatName={currentChatName}
          messages={messages}
          isLoading={isLoading}
          isLoadingOlder={isLoadingOlder}
          hasMoreMessages={currentChatId !== "public" && hasMoreGroupMessages}
          isConnected={wsConnected}
          username={username}
          onSendMessage={sendMessage}
          onMediaMessageDelivered={handleMediaMessageDelivered}
          onLoadOlderMessages={loadOlderGroupMessages}
          onLogout={onLogout}
        />
        {showCreateGroupModal && (
          <CreateGroupModal
            onClose={() => setShowCreateGroupModal(false)}
            onGroupCreated={handleGroupCreated}
          />
        )}
      </Box>
    </div>
  );
}

export default ChatPage;
