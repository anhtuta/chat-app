import React, { useState, useEffect, useRef } from "react";
import { useNavigate, useParams } from "react-router-dom";
import Sidebar from "../components/Sidebar";
import ChatArea from "../components/ChatArea";
import CreateGroupModal from "../components/CreateGroupModal";
import { getGroups, getPublicMessages, getGroupMessages } from "../services/api";
import { useWebSocket } from "../context/WebSocketProvider";

function ChatPage({ username, onLogout }) {
  const GROUP_PAGE_SIZE = 10;

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
  const currentGroupPageRef = useRef(0);

  const currentChatIdRef = useRef("public");
  const { isConnected: wsConnected, subscribe: subscribeTopic, sendMessage: sendWebSocketMessage } = useWebSocket();

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

    const group = groups.find((g) => g.id === numericId);
    if (group) {
      switchToChat(group.id, group.name);
    }
  }, [groupId, groups]);


  const loadGroups = async () => {
    try {
      const groupsData = await getGroups();
      setGroups(groupsData);
    } catch (error) {
      console.error("Error loading groups:", error);
    }
  };

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
    currentGroupPageRef.current = 0;

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
      loadGroupMessages(chatId, { page: 0, prepend: false });
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

  const loadGroupMessages = async (groupId, { page = 0, prepend = false } = {}) => {
    if (prepend) {
      setIsLoadingOlder(true);
    } else {
      setIsLoading(true);
    }

    try {
      const messagesData = await getGroupMessages(groupId, page, GROUP_PAGE_SIZE);

      if (prepend) {
        setMessages((prev) => [...messagesData, ...prev]);
      } else {
        setMessages(messagesData);
      }

      currentGroupPageRef.current = page;
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

    const nextPage = currentGroupPageRef.current + 1;
    await loadGroupMessages(currentChatId, { page: nextPage, prepend: true });
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
    <div className="chat-container">
      <Sidebar
        groups={groups}
        currentChatId={currentChatId}
        onChatSelect={handleChatNavigate}
        onCreateGroupClick={() => setShowCreateGroupModal(true)}
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
    </div>
  );
}

export default ChatPage;
