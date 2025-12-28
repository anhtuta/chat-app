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
  const [currentChatType, setCurrentChatType] = useState("public");
  const [currentChatId, setCurrentChatId] = useState(null);
  const [currentChatName, setCurrentChatName] = useState("Public Chat");
  const [messages, setMessages] = useState([]);
  const [isConnected, setIsConnected] = useState(false);
  const [isLoading, setIsLoading] = useState(false);
  const [showCreateGroupModal, setShowCreateGroupModal] = useState(false);

  const groupSubscriptions = useRef({});
  const publicSubscription = useRef(null);
  const currentChatRef = useRef({ type: "public", id: null });
  const { isConnected: wsConnected, subscribe: subscribeTopic, sendMessage: sendWebSocketMessage } = useWebSocket();

  useEffect(() => {
    loadGroups();

    return () => {
      // Cleanup on unmount
      Object.values(groupSubscriptions.current).forEach((unsubscribe) => {
        if (unsubscribe) unsubscribe();
      });
      if (publicSubscription.current) {
        publicSubscription.current();
      }
    };
  }, []);

  // Ensure we keep a single public subscription that auto re-subscribes on reconnect
  useEffect(() => {
    if (publicSubscription.current) return;
    publicSubscription.current = subscribeTopic("/topic/public", (message) => {
      if (currentChatRef.current.type === "public") {
        setMessages((prev) => [...prev, message]);
      }
    });
  }, [subscribeTopic]);

  useEffect(() => {
    // Handle route changes
    if (!groupId || groupId === "public") {
      switchToChat("public", null, "Public Chat");
      return;
    }

    const numericId = Number(groupId);
    if (Number.isNaN(numericId)) {
      switchToChat("public", null, "Public Chat");
      return;
    }

    const group = groups.find((g) => g.id === numericId);
    if (group) {
      switchToChat("group", group.id, group.name);
    }
  }, [groupId, groups]);

  // Track connection state from provider
  useEffect(() => {
    setIsConnected(wsConnected);
    // On first connect, load current chat messages
    if (wsConnected && currentChatRef.current.type === "public") {
      loadMessages();
    } else if (wsConnected && currentChatRef.current.type === "group" && currentChatRef.current.id) {
      loadGroupMessages(currentChatRef.current.id);
    }
  }, [wsConnected]);

  const loadGroups = async () => {
    try {
      const groupsData = await getGroups();
      setGroups(groupsData);
    } catch (error) {
      console.error("Error loading groups:", error);
    }
  };

  const switchToChat = async (type, chatId, chatName) => {
    // Update ref immediately so subscriptions can use the latest value
    currentChatRef.current = { type, id: chatId };

    // Unsubscribe from previous chat
    if (currentChatType === "group" && currentChatId) {
      const subscription = groupSubscriptions.current[currentChatId];
      if (subscription) {
        subscription.unsubscribe();
        delete groupSubscriptions.current[currentChatId];
      }
    }

    // Update current chat
    setCurrentChatType(type);
    setCurrentChatId(chatId);
    setCurrentChatName(chatName || "Public Chat");
    setMessages([]);

    // Subscribe to new chat
    if (type === "public") {
      loadMessages();
    } else if (type === "group" && chatId) {
      const unsubscribe = subscribeTopic(`/topic/group.${chatId}`, (message) => {
        if (currentChatRef.current.type === "group" && currentChatRef.current.id === chatId) {
          setMessages((prev) => [...prev, message]);
        }
      });
      groupSubscriptions.current[chatId] = unsubscribe;
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
    if (!content.trim() || !isConnected) {
      return;
    }

    const chatMessage = { content };

    if (currentChatType === "public") {
      sendWebSocketMessage("/app/chat.send", chatMessage);
    } else if (currentChatType === "group" && currentChatId) {
      chatMessage.groupId = currentChatId;
      sendWebSocketMessage("/app/group.send", chatMessage);
    }
  };

  const handleGroupCreated = (newGroup) => {
    loadGroups();
    switchToChat("group", newGroup.id, newGroup.name);
  };

  const handleChatNavigate = (type, chatId, chatName) => {
    if (type === "public") {
      navigate("/group/public");
    } else if (type === "group" && chatId) {
      navigate(`/group/${chatId}`);
    }
  };

  return (
    <div className="chat-container">
      <Sidebar
        groups={groups}
        currentChatType={currentChatType}
        currentChatId={currentChatId}
        onChatSelect={handleChatNavigate}
        onCreateGroupClick={() => setShowCreateGroupModal(true)}
      />
      <ChatArea
        chatName={currentChatName}
        messages={messages}
        isLoading={isLoading}
        isConnected={isConnected}
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
