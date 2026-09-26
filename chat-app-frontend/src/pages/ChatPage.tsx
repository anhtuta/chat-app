import React, { useState, useEffect, useRef } from "react";
import { useNavigate, useParams, useLocation } from "react-router-dom";
import { Box } from "@mui/material";
import Sidebar from "../components/Sidebar";
import ChatArea from "../components/ChatArea";
import CreateGroupModal from "../components/CreateGroupModal";
import GroupDetailsDialog from "../components/group-details/GroupDetailsDialog";
import { getGroups, getPublicMessages, getGroupMessages, markGroupAsRead } from "../services/api";
import { SYSTEM_EVENT_TYPES } from "../constant/systemEventTypes";
import { useWebSocket } from "../context/WebSocketProvider";
import { buildLatestMessagePreviewFromMessage } from "../utils/messageModeration";
import { applyGroupSummaryUpdate } from "../utils/groupSummaryUpdates";
import type { ChatMessage } from "../types/chat";
import type { ChatGroup } from "../types/groups";
import type { ThemeId, ThemeOption } from "../types/theme";
import type { Unsubscribe } from "../types/websocket";

type ChatRouteId = "public" | number;

interface GroupMessageCursor {
  timestamp: string;
  id: number;
}

interface ChatPageLocationState {
  joinedViaLink?: boolean;
  groupName?: string;
}

interface ChatPageProps {
  username: string | null;
  fullname?: string | null;
  onLogout: () => void | Promise<void>;
  selectedThemeId: ThemeId;
  onThemeChange: (themeId: ThemeId) => void;
  themeOptions: ThemeOption[];
}

function ChatPage({
  username,
  fullname,
  onLogout,
  selectedThemeId,
  onThemeChange,
  themeOptions,
}: ChatPageProps) {
  const GROUP_PAGE_SIZE = 10;
  const MAX_TITLE_LENGTH = 50;

  const toEpochMillis = (value: string | null | undefined): number => {
    if (!value) {
      return 0;
    }
    const parsed = Date.parse(value);
    return Number.isNaN(parsed) ? 0 : parsed;
  };

  const getMessageFreshnessEpochMillis = (message: ChatMessage | null | undefined): number => {
    if (!message) {
      return 0;
    }

    // Prefer the server-computed revision key. Fall back to the original timestamp only
    // during mixed-version rollouts where older responses may not include freshnessKey yet.
    const freshnessMillis = toEpochMillis(message.freshnessKey);
    if (freshnessMillis > 0) {
      return freshnessMillis;
    }

    return toEpochMillis(message.timestamp);
  };

  const keepFresherMessage = (
    currentMessage: ChatMessage,
    candidateMessage: ChatMessage,
  ): ChatMessage => {
    const freshnessDiff = getMessageFreshnessEpochMillis(candidateMessage)
      - getMessageFreshnessEpochMillis(currentMessage);

    if (freshnessDiff > 0) {
      return candidateMessage;
    }

    return currentMessage;
  };

  const navigate = useNavigate();
  const location = useLocation();
  const { groupId } = useParams<{ groupId?: string }>();
  const locationState = (location.state || {}) as ChatPageLocationState;

  const [groups, setGroups] = useState<ChatGroup[]>([]);
  const [currentChatId, setCurrentChatId] = useState<ChatRouteId>("public");
  const [currentChatName, setCurrentChatName] = useState("Public Chat");
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [isLoading, setIsLoading] = useState(false);
  const [isLoadingOlder, setIsLoadingOlder] = useState(false);
  const [hasMoreGroupMessages, setHasMoreGroupMessages] = useState(true);
  const [showCreateGroupModal, setShowCreateGroupModal] = useState(false);
  const [showGroupDetailsDialog, setShowGroupDetailsDialog] = useState(false);
  const [roleChangeSignal, setRoleChangeSignal] = useState(0);
  const [profileChangeSignal, setProfileChangeSignal] = useState(0);

  // Hold the current topic's unsubscribe function so we can cleanly unsubscribe when switching chats or unmounting.
  const currentUnsubscribeRef = useRef<Unsubscribe | null>(null);
  const oldestGroupCursorRef = useRef<GroupMessageCursor | null>(null);
  const messagesRef = useRef<ChatMessage[]>([]);
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
    nextMessages[existingIndex] = keepFresherMessage(nextMessages[existingIndex], incomingMessage);
    return nextMessages;
  };

  const mergeMessagesById = (fetchedMessages: ChatMessage[], currentMessages: ChatMessage[]) => {
    const merged: ChatMessage[] = [];
    const indexById = new Map<number, number>();

    const addMessage = (message: ChatMessage) => {
      if (message.id === undefined || message.id === null) {
        merged.push(message);
        return;
      }

      const existingIndex = indexById.get(message.id);
      if (existingIndex === undefined) {
        indexById.set(message.id, merged.length);
        merged.push(message);
        return;
      }

      merged[existingIndex] = keepFresherMessage(merged[existingIndex], message);
    };

    fetchedMessages.forEach(addMessage);
    currentMessages.forEach(addMessage);
    return merged;
  };

  const compareMessagesChronologically = (left: ChatMessage, right: ChatMessage) => {
    const timestampDiff = toEpochMillis(left.timestamp) - toEpochMillis(right.timestamp);
    if (timestampDiff !== 0) {
      return timestampDiff;
    }

    const leftId = left.id ?? Number.MAX_SAFE_INTEGER;
    const rightId = right.id ?? Number.MAX_SAFE_INTEGER;
    return leftId - rightId;
  };

  const isRoleChangeSystemMessage = (message: ChatMessage | null | undefined): boolean => (
    message?.messageType === "SYSTEM"
    && (
      message.systemEventType === SYSTEM_EVENT_TYPES.USER_PROMOTED
      || message.systemEventType === SYSTEM_EVENT_TYPES.USER_DEMOTED
      || message.systemEventType === SYSTEM_EVENT_TYPES.LEADERSHIP_TRANSFERRED
    )
  );

  const isProfileChangeSystemMessage = (message: ChatMessage | null | undefined): boolean => (
    message?.messageType === "SYSTEM"
    && (
      message.systemEventType === SYSTEM_EVENT_TYPES.GROUP_NAME_UPDATED
      || message.systemEventType === SYSTEM_EVENT_TYPES.GROUP_DESCRIPTION_UPDATED
      || message.systemEventType === SYSTEM_EVENT_TYPES.GROUP_ARCHIVED
    )
  );

  useEffect(() => {
    const title = currentChatName.length > MAX_TITLE_LENGTH ? currentChatName.substring(0, MAX_TITLE_LENGTH) + "..." : currentChatName;
    document.title = `${title} | Chat App`;
  }, [currentChatName])

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

  useEffect(() => {
    messagesRef.current = messages;
  }, [messages]);

  // Register sidebar update handler. The STOMP subscription itself is owned by WebSocketProvider
  // for the whole login session and is not tied to chat switching.
  useEffect(() => {
    setGroupUpdatesHandler((groupSummaryUpdate) => {
      const updatedGroupId = Number(groupSummaryUpdate.groupId);

      if (groupSummaryUpdate.removed) {
        setGroups((prev) => prev.filter((group) => Number(group.id) !== updatedGroupId));
        if (currentChatIdRef.current === updatedGroupId) {
          currentUnsubscribeRef.current?.();
          currentUnsubscribeRef.current = null;
          navigate("/group/public");
        }
        return;
      }

      setGroups((prev) => applyGroupSummaryUpdate(prev, groupSummaryUpdate, currentChatIdRef.current));
    });

    return () => setGroupUpdatesHandler(null);
  }, [navigate, setGroupUpdatesHandler]);

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

  const updateOldestGroupCursor = (oldestMessage: ChatMessage | undefined) => {
    if (!oldestMessage?.timestamp || oldestMessage.id === undefined || oldestMessage.id === null) {
      oldestGroupCursorRef.current = null;
      return;
    }

    oldestGroupCursorRef.current = {
      timestamp: oldestMessage.timestamp,
      id: oldestMessage.id,
    };
  };

  useEffect(() => {
    updateOldestGroupCursor(messages[0]);
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
    switchToChat(
      numericId,
      group?.name || locationState.groupName || `Group ${numericId}`,
    );
    // groupsRef is stable; switchToChat is intentionally omitted to avoid re-syncing on every render.
    // eslint-disable-next-line react-hooks/exhaustive-deps -- only react to URL param changes
  }, [groupId]);

  useEffect(() => {
    if (currentChatId === "public") {
      return;
    }

    const selectedGroup = groups.find((group) => Number(group.id) === Number(currentChatId));
    if (selectedGroup?.name && selectedGroup.name !== currentChatName) {
      setCurrentChatName(selectedGroup.name);
    }
  }, [currentChatId, currentChatName, groups]);

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
  const currentGroup = currentChatId === "public"
    ? null
    : (groups.find((group) => Number(group.id) === Number(currentChatId)) || null);

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
    messagesRef.current = [];
    setIsLoadingOlder(false);
    setHasMoreGroupMessages(true);
    oldestGroupCursorRef.current = null;

    // Subscribe to new topic (works for both public and groups)
    const topicPath = chatId === "public" ? "/topic/public" : `/topic/group.${chatId}`;
    const unsubscribe = subscribeSingleGroup(topicPath, (message) => {
      if (currentChatIdRef.current === chatId) {
        setMessages((prev) => {
          const nextMessages = upsertMessage(prev, message);
          messagesRef.current = nextMessages;
          return nextMessages;
        });
        if (chatId !== "public" && isRoleChangeSystemMessage(message)) {
          setRoleChangeSignal((previous) => previous + 1);
        }
        if (chatId !== "public" && isProfileChangeSystemMessage(message)) {
          setProfileChangeSignal((previous) => previous + 1);
        }
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
      messagesRef.current = messagesData;
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
      const messagesData = prepend
        ? await getGroupMessages(targetGroupId, {
          size: GROUP_PAGE_SIZE,
          beforeTimestamp: cursor?.timestamp,
          beforeId: cursor?.id,
        })
        : await fetchLatestGroupMessages(targetGroupId, GROUP_PAGE_SIZE);

      if (currentChatIdRef.current !== targetGroupId) {
        return [];
      }

      if (prepend) {
        const previousMessages = messagesRef.current;
        const nextMessages = mergeMessagesById(messagesData, previousMessages)
          .sort(compareMessagesChronologically);
        const didChange = nextMessages.length !== previousMessages.length
          || nextMessages.some((message, index) => message !== previousMessages[index]);

        if (!didChange) {
          setHasMoreGroupMessages(messagesData.length === GROUP_PAGE_SIZE);
          return [];
        }

        messagesRef.current = nextMessages;
        updateOldestGroupCursor(nextMessages[0]);
        setMessages(nextMessages);
        setHasMoreGroupMessages(messagesData.length === GROUP_PAGE_SIZE);
        return messagesData;
      }

      setMessages((currentMessages) => {
        const mergedMessages = mergeMessagesById(messagesData, currentMessages)
          .sort(compareMessagesChronologically);
        messagesRef.current = mergedMessages;
        updateOldestGroupCursor(mergedMessages[0]);
        return mergedMessages;
      });
      setHasMoreGroupMessages(messagesData.length === GROUP_PAGE_SIZE);
      return messagesData;
    } catch (error) {
      console.error("Error loading group messages:", error);
      return [];
    } finally {
      if (currentChatIdRef.current === targetGroupId) {
        if (prepend) {
          setIsLoadingOlder(false);
        } else {
          setIsLoading(false);
        }
      }
    }
  };

  const loadOlderGroupMessages = async () => {
    if (currentChatId === "public" || isLoading || isLoadingOlder || !hasMoreGroupMessages) {
      return false;
    }

    const cursor = oldestGroupCursorRef.current;
    if (!cursor) {
      return false;
    }

    const olderMessages = await loadGroupMessages(currentChatId, {
      prepend: true,
      cursor,
    });
    return olderMessages.length > 0;
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

    setMessages((prev) => {
      const nextMessages = upsertMessage(prev, message);
      messagesRef.current = nextMessages;
      return nextMessages;
    });
  };

  const handleMessageModerated = (updatedMessage: ChatMessage | null | undefined) => {
    if (!updatedMessage) {
      return;
    }

    const targetChatId = updatedMessage.groupId === undefined || updatedMessage.groupId === null
      ? "public"
      : Number(updatedMessage.groupId);
    if (currentChatIdRef.current !== targetChatId) {
      return;
    }

    setMessages((prev) => {
      const nextMessages = upsertMessage(prev, updatedMessage);
      messagesRef.current = nextMessages;
      return nextMessages;
    });

    if (updatedMessage.groupId === undefined || updatedMessage.groupId === null) {
      return;
    }

    const moderatedGroupId = Number(updatedMessage.groupId);
    const moderatedTimestamp = toEpochMillis(updatedMessage.timestamp);
    setGroups((prev) => prev.map((group) => {
      if (Number(group.id) !== moderatedGroupId) {
        return group;
      }

      const currentLatestTimestamp = toEpochMillis(group.latestMessageAt);
      if (moderatedTimestamp < currentLatestTimestamp) {
        return group;
      }

      return {
        ...group,
        latestMessage: buildLatestMessagePreviewFromMessage(updatedMessage),
        latestMessageSender: updatedMessage.user?.username || group.latestMessageSender,
        latestMessageAt: updatedMessage.timestamp ?? group.latestMessageAt,
      };
    }));
  };

  const handleChatNavigate = (chatId: ChatRouteId) => {
    navigate(`/group/${chatId}`);
  };

  const handleGroupUpdated = (updatedGroup: ChatGroup) => {
    setGroups((previousGroups) => previousGroups.map((group) => (
      Number(group.id) === Number(updatedGroup.id)
        ? {
          ...group,
          ...updatedGroup,
          // Prefer caller-specific fields from details refresh when present (e.g. after leadership transfer).
          unreadCount: updatedGroup.unreadCount ?? group.unreadCount,
          currentUserRole: updatedGroup.currentUserRole ?? group.currentUserRole,
          currentUserPermissions:
            updatedGroup.currentUserPermissions ?? group.currentUserPermissions,
        }
        : group
    )));

    if (currentChatIdRef.current === Number(updatedGroup.id) && updatedGroup.name) {
      setCurrentChatName(updatedGroup.name);
    }
  };

  const handleGroupLeft = (leftGroupId: number | string) => {
    setShowGroupDetailsDialog(false);
    setGroups((previousGroups) =>
      previousGroups.filter((group) => Number(group.id) !== Number(leftGroupId)),
    );
    if (currentChatIdRef.current === Number(leftGroupId)) {
      navigate("/group/public");
    }
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
          onJoinGroupClick={() => navigate("/join")}
          selectedThemeId={selectedThemeId}
          onThemeChange={onThemeChange}
          themeOptions={themeOptions}
          username={username}
          fullname={fullname}
          onLogout={onLogout}
        />
        <ChatArea
          chatId={currentChatId}
          chatName={currentChatName}
          currentGroup={currentGroup}
          messages={messages}
          isLoading={isLoading}
          isLoadingOlder={isLoadingOlder}
          hasMoreMessages={currentChatId !== "public" && hasMoreGroupMessages}
          isConnected={wsConnected}
          username={username}
          onSendMessage={sendMessage}
          onMediaMessageDelivered={handleMediaMessageDelivered}
          onMessageModerated={handleMessageModerated}
          onLoadOlderMessages={loadOlderGroupMessages}
          onOpenGroupDetails={() => setShowGroupDetailsDialog(true)}
        />
        {showGroupDetailsDialog && currentGroup ? (
          <GroupDetailsDialog
            open={showGroupDetailsDialog}
            groupId={currentGroup.id}
            initialGroup={currentGroup}
            currentUsername={username}
            onClose={() => setShowGroupDetailsDialog(false)}
            onGroupUpdated={handleGroupUpdated}
            onGroupLeft={handleGroupLeft}
            roleChangeSignal={roleChangeSignal}
            profileChangeSignal={profileChangeSignal}
          />
        ) : null}
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

const inFlightInitialGroupMessageLoads = new Map<number, Promise<ChatMessage[]>>();

function fetchLatestGroupMessages(groupId: number, size: number): Promise<ChatMessage[]> {
  const existingRequest = inFlightInitialGroupMessageLoads.get(groupId);
  if (existingRequest) {
    return existingRequest;
  }

  const request = getGroupMessages(groupId, { size }).finally(() => {
    inFlightInitialGroupMessageLoads.delete(groupId);
  });
  inFlightInitialGroupMessageLoads.set(groupId, request);
  return request;
}
