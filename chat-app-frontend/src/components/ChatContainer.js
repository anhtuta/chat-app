import React, { useState, useEffect, useRef } from "react";
import { useParams } from "react-router-dom";
import Sidebar from "./Sidebar";
import ChatArea from "./ChatArea";
import CreateGroupModal from "./CreateGroupModal";
import { getGroups, getPublicMessages, getGroupMessages } from "../services/api";
import {
  connectWebSocket,
  disconnectWebSocket,
  subscribeToTopic,
  sendMessage as sendWebSocketMessage,
  getStompClient,
} from "../services/websocket";

function ChatContainer({ username, onLogout }) {
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

  useEffect(() => {
    loadGroups();
    connect();

    return () => {
      // Cleanup on unmount
      Object.values(groupSubscriptions.current).forEach((sub) => {
        if (sub) sub.unsubscribe();
      });
      disconnectWebSocket();
    };
  }, []);

  useEffect(() => {
    // Handle route changes
    if (groupId) {
      const group = groups.find((g) => g.id === parseInt(groupId));
      if (group) {
        switchToChat("group", group.id, group.name);
      }
    } else {
      switchToChat("public", null, "Public Chat");
    }
  }, [groupId, groups]);

  const connect = () => {
    connectWebSocket(
      (frame) => {
        setIsConnected(true);
        // Subscribe to public messages
        subscribeToTopic("/topic/public", (message) => {
          if (currentChatType === "public") {
            addMessage(message);
          }
        });
        // Load messages for current chat
        if (currentChatType === "public") {
          loadMessages();
        }
      },
      (error) => {
        console.error("Connection error:", error);
        setIsConnected(false);
      }
    );
  };

  const loadGroups = async () => {
    try {
      const groupsData = await getGroups();
      setGroups(groupsData);
    } catch (error) {
      console.error("Error loading groups:", error);
    }
  };

  const switchToChat = async (type, chatId, chatName) => {
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
      // Already subscribed in connect()
      loadMessages();
    } else if (type === "group" && chatId) {
      const subscription = subscribeToTopic(`/topic/group.${chatId}`, (message) => {
        addMessage(message);
      });
      if (subscription) {
        groupSubscriptions.current[chatId] = subscription;
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

  const addMessage = (message) => {
    setMessages((prev) => [...prev, message]);
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

  return (
    <div className="chat-container">
      <Sidebar
        groups={groups}
        currentChatType={currentChatType}
        currentChatId={currentChatId}
        onChatSelect={switchToChat}
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

export default ChatContainer;
