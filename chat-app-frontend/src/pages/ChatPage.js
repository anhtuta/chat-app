import React, { useState, useEffect, useRef } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { Box } from "@mui/material";
import Sidebar from "../components/Sidebar";
import ChatArea from "../components/ChatArea";
import CreateGroupModal from "../components/CreateGroupModal";
import { getGroups, getPublicMessages, getGroupMessages, markGroupAsRead } from "../services/api";
import { useWebSocket } from "../context/WebSocketProvider";

function ChatPage({ username, onLogout, selectedThemeId, onThemeChange, themeOptions }) {
  const GROUP_PAGE_SIZE = 10;

  const toEpochMillis = (value) => {
    if (!value) {
      return 0;
    }
    const parsed = Date.parse(value);
    return Number.isNaN(parsed) ? 0 : parsed;
  };

  const navigate = useNavigate();
  const { groupId } = useParams();

  const [groups, setGroups] = useState([]);
  const [currentChatId, setCurrentChatId] = useState("public");
  const [currentChatName, setCurrentChatName] = useState("Public Chat");
  const [messages, setMessages] = useState([]);
  const [isLoading, setIsLoading] = useState(false);
  const [isLoadingOlder, setIsLoadingOlder] = useState(false);
  const [hasMoreGroupMessages, setHasMoreGroupMessages] = useState(true);
  const [showCreateGroupModal, setShowCreateGroupModal] = useState(false);

  // Hold the current topic's unsubscribe function so we can cleanly unsubscribe when switching chats or unmounting.
  const currentUnsubscribeRef = useRef(null);
  const oldestGroupCursorRef = useRef(null);
  const groupsRef = useRef([]);
  const lastMarkedMessagePerGroupRef = useRef(new Map());

  const currentChatIdRef = useRef("public");
  const { isConnected: wsConnected, subscribe: subscribeTopic, subscribePersonal, sendMessage: sendWebSocketMessage } = useWebSocket();

  useEffect(() => {
    loadGroups();

    return () => {
      // Cleanup current subscription on unmount
      if (currentUnsubscribeRef.current) {
        currentUnsubscribeRef.current();
      }
    };
  }, []);

  useEffect(() => {
    groupsRef.current = groups;
  }, [groups]);

  // Subscribe to personal group-summary updates so the sidebar refreshes in real time
  // when other group members send messages, without requiring a poll or page reload.
  useEffect(() => {
    if (!wsConnected || !username) return;

    const destination = `/topic/user.${username}.group-updates`;

    // Type of groupSummaryUpdate is defined in backend as com.hello.chatapp.dto.GroupSummaryUpdate.
    const unsubscribe = subscribePersonal(destination, (groupSummaryUpdate) => {
      const updatedGroupId = Number(groupSummaryUpdate.groupId);
      setGroups((prev) => {
        const groupIndex = prev.findIndex((g) => Number(g.id) === updatedGroupId);
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

        const updatedGroup = {
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

    return unsubscribe;
  }, [wsConnected, username, subscribePersonal]);

  useEffect(() => {
    if (currentChatId === "public" || isLoading) {
      return;
    }

    const latestVisibleMessage = messages[messages.length - 1];
    const latestVisibleMessageId = latestVisibleMessage?.id;
    if (latestVisibleMessageId === undefined || latestVisibleMessageId === null) {
      return;
    }

    const groupId = Number(currentChatId);
    const lastMarkedMessageId = lastMarkedMessagePerGroupRef.current.get(groupId);
    if (lastMarkedMessageId === latestVisibleMessageId) {
      return;
    }

    markGroupAsRead(groupId, latestVisibleMessageId)
      .then(() => {
        lastMarkedMessagePerGroupRef.current.set(groupId, latestVisibleMessageId);
        setGroups((prev) => prev.map((group) => (
          Number(group.id) === groupId
            ? { ...group, unreadCount: 0 }
            : group
        )));
      })
      .catch((error) => {
        console.error("Error marking group as read:", error);
      });
  }, [currentChatId, messages, isLoading]);

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
    // Handle route changes
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

  const switchToChat = async (chatId, chatName) => {
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
    const topicPath = chatId === 'public' ? '/topic/public' : `/topic/group.${chatId}`;
    const unsubscribe = subscribeTopic(topicPath, (message) => {
      if (currentChatIdRef.current === chatId) {
        setMessages((prev) => [...prev, message]);
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

  const loadGroupMessages = async (groupId, { prepend = false, cursor = null } = {}) => {
    if (prepend) {
      setIsLoadingOlder(true);
    } else {
      setIsLoading(true);
    }

    try {
      const messagesData = await getGroupMessages(groupId, {
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

  const sendMessage = (content) => {
    if (!content.trim() || !wsConnected) {
      return;
    }

    const chatMessage = { content };

    if (currentChatId === 'public') {
      sendWebSocketMessage("/app/chat.send", chatMessage);
    } else {
      chatMessage.groupId = currentChatId;
      sendWebSocketMessage("/app/group.send", chatMessage);
    }
  };

  const handleGroupCreated = (newGroup) => {
    loadGroups();
    switchToChat(newGroup.id, newGroup.name);
  };

  const handleChatNavigate = (chatId) => {
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
          onLoadOlderMessages={loadOlderGroupMessages}
          onLogout={onLogout}
        />
        {showCreateGroupModal && (
          <CreateGroupModal onClose={() => setShowCreateGroupModal(false)} onGroupCreated={handleGroupCreated} />
        )}
      </Box>
    </div>
  );
}

export default ChatPage;
