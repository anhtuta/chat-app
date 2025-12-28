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
  const [isConnected, setIsConnected] = useState(false);
  const [isLoading, setIsLoading] = useState(false);
  const [showCreateGroupModal, setShowCreateGroupModal] = useState(false);

  const subscriptionsRef = useRef({});
  const currentChatIdRef = useRef("public");
  const { isConnected: wsConnected, subscribe: subscribeTopic, sendMessage: sendWebSocketMessage } = useWebSocket();

  useEffect(() => {
    loadGroups();

    return () => {
      // Cleanup all subscriptions on unmount
      Object.values(subscriptionsRef.current).forEach((unsubscribe) => {
        if (unsubscribe) unsubscribe();
      });
    };
  }, []);

  // Subscribe to public chat on mount
  useEffect(() => {
    if (subscriptionsRef.current['public']) return;
    subscriptionsRef.current['public'] = subscribeTopic("/topic/public", (message) => {
      if (currentChatIdRef.current === 'public') {
        setMessages((prev) => [...prev, message]);
      }
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps
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

  // Track connection state from provider
  useEffect(() => {
    setIsConnected(wsConnected);
    // On first connect, load current chat messages
    if (wsConnected) {
      if (currentChatIdRef.current === 'public') {
        loadMessages();
      } else {
        loadGroupMessages(currentChatIdRef.current);
      }
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

  const switchToChat = async (chatId, chatName) => {
    // Update ref immediately so subscriptions can use the latest value
    currentChatIdRef.current = chatId;

    // Unsubscribe from previous chat (except public which stays subscribed)
    if (currentChatId !== 'public' && currentChatId !== chatId) {
      const unsubscribeFn = subscriptionsRef.current[currentChatId];
      if (unsubscribeFn) {
        unsubscribeFn();
        delete subscriptionsRef.current[currentChatId];
      }
    }

    // Update current chat
    setCurrentChatId(chatId);
    setCurrentChatName(chatName || "Public Chat");
    setMessages([]);

    // Load messages and subscribe if needed
    if (chatId === 'public') {
      loadMessages();
      // Public is already subscribed on mount
    } else {
      // Subscribe to group topic if not already subscribed
      if (!subscriptionsRef.current[chatId]) {
        const unsubscribe = subscribeTopic(`/topic/group.${chatId}`, (message) => {
          if (currentChatIdRef.current === chatId) {
            setMessages((prev) => [...prev, message]);
          }
        });
        subscriptionsRef.current[chatId] = unsubscribe;
      }
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
