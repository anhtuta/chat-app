import React, { useState, useEffect, useRef } from "react";
import { useNavigate, useParams } from "react-router-dom";
import Sidebar from "../components/Sidebar";
import ChatArea from "../components/ChatArea";
import CreateGroupModal from "../components/CreateGroupModal";
import { getGroups, getPublicMessages, getGroupMessages } from "../services/api";
import { useWebSocket } from "../context/WebSocketProvider";

function ChatPage({ username, onLogout }) {
  const navigate = useNavigate();
  const { groupId } = useParams();

  const [groups, setGroups] = useState([]);
  const [currentChatId, setCurrentChatId] = useState("public");
  const [currentChatName, setCurrentChatName] = useState("Public Chat");
  const [messages, setMessages] = useState([]);
  const [isLoading, setIsLoading] = useState(false);
  const [showCreateGroupModal, setShowCreateGroupModal] = useState(false);

  // Hold the current topic's unsubscribe function so we can cleanly unsubscribe when switching chats or unmounting.
  const currentUnsubscribeRef = useRef(null);

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
      loadGroupMessages(chatId);
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

  const loadGroupMessages = async (groupId) => {
    setIsLoading(true);
    try {
      const messagesData = await getGroupMessages(groupId);
      setMessages(messagesData);
    } catch (error) {
      console.error("Error loading group messages:", error);
    } finally {
      setIsLoading(false);
    }
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
    if (chatId === 'public') {
      navigate("/group/public");
    } else {
      navigate(`/group/${chatId}`);
    }
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
        chatName={currentChatName}
        messages={messages}
        isLoading={isLoading}
        isConnected={wsConnected}
        username={username}
        onSendMessage={sendMessage}
        onLogout={onLogout}
      />
      {showCreateGroupModal && (
        <CreateGroupModal onClose={() => setShowCreateGroupModal(false)} onGroupCreated={handleGroupCreated} />
      )}
    </div>
  );
}

export default ChatPage;
