import React, { useState, useEffect, useRef } from "react";
import "./ChatArea.css";

function ChatArea({
  chatId,
  chatName,
  messages,
  isLoading,
  isLoadingOlder,
  hasMoreMessages,
  isConnected,
  username,
  onSendMessage,
  onLoadOlderMessages,
  onLogout,
}) {
  const [messageInput, setMessageInput] = useState("");
  const chatMessagesRef = useRef(null);
  const messagesEndRef = useRef(null);
  const isPrependingRef = useRef(false);
  const isLoadingOlderRef = useRef(false);

  useEffect(() => {
    isLoadingOlderRef.current = isLoadingOlder;
  }, [isLoadingOlder]);

  useEffect(() => {
    if (isPrependingRef.current) {
      return;
    }
    scrollToBottom();
  }, [messages]);

  useEffect(() => {
    isPrependingRef.current = false;
  }, [chatId]);

  const scrollToBottom = () => {
    messagesEndRef.current?.scrollIntoView({ behavior: "smooth" });
  };

  const handleMessagesScroll = async (event) => {
    const container = event.currentTarget;
    if (
      container.scrollTop > 40 ||
      !hasMoreMessages ||
      isLoadingOlderRef.current ||
      isLoading ||
      !onLoadOlderMessages
    ) {
      return;
    }

    const previousHeight = container.scrollHeight;
    const previousTop = container.scrollTop;

    isPrependingRef.current = true;
    const loaded = await onLoadOlderMessages();

    if (!loaded) {
      isPrependingRef.current = false;
      return;
    }

    requestAnimationFrame(() => {
      const updatedHeight = container.scrollHeight;
      container.scrollTop = updatedHeight - previousHeight + previousTop;
      isPrependingRef.current = false;
    });
  };

  const handleSend = () => {
    if (messageInput.trim()) {
      onSendMessage(messageInput);
      setMessageInput("");
    }
  };

  const handleKeyPress = (e) => {
    if (e.key === "Enter") {
      handleSend();
    }
  };

  const formatMessage = (message) => {
    const isSystemMessage = message.content && message.content.startsWith("[SYSTEM] ");

    if (isSystemMessage) {
      const systemContent = message.content.replace("[SYSTEM] ", "").toLowerCase();
      const isDisconnected = systemContent.includes("disconnected");
      const isConnected = systemContent.includes("connected");

      return {
        type: "system",
        content: message.content.replace("[SYSTEM] ", ""),
        isDisconnected,
        isConnected,
      };
    }

    const displayName =
      message.user && message.user.fullname ? message.user.fullname : message.user ? message.user.username : "Unknown";
    const messageUsername = message.user ? message.user.username : null;
    const isOwnMessage = messageUsername === username;

    return {
      type: isOwnMessage ? "sent" : "received",
      displayName,
      content: message.content,
      timestamp: message.timestamp,
    };
  };

  return (
    <div className="main-chat">
      <div className="chat-header">
        <div className="header-title">{chatName}</div>
        <button className="logout-button" onClick={onLogout}>
          Logout
        </button>
      </div>
      <div className={`status ${isConnected ? "connected" : "disconnected"}`}>
        {isConnected ? "Connected" : "Disconnected"}
      </div>
      {isLoading && <div className="loading-indicator show">Loading previous messages</div>}
      <div ref={chatMessagesRef} className="chat-messages" onScroll={handleMessagesScroll}>
        {isLoadingOlder && <div className="loading-older">Loading older messages...</div>}
        {messages.map((message, index) => {
          const formatted = formatMessage(message);

          if (formatted.type === "system") {
            return (
              <div
                key={index}
                className={`message system ${formatted.isDisconnected ? "disconnected" : ""} ${formatted.isConnected ? "connected" : ""
                  }`}
              >
                <div className="message-content">{formatted.content}</div>
              </div>
            );
          }

          return (
            <div key={message.id || index} className={`message ${formatted.type}`}>
              <div className="message-header">
                {formatted.displayName} • {new Date(formatted.timestamp).toLocaleTimeString()}{" "}
                {new Date(formatted.timestamp).toLocaleDateString()}
              </div>
              <div className="message-content">{formatted.content}</div>
            </div>
          );
        })}
        <div ref={messagesEndRef} />
      </div>
      <div className="chat-input-container">
        <input
          type="text"
          className="message-input"
          placeholder="Type a message..."
          value={messageInput}
          onChange={(e) => setMessageInput(e.target.value)}
          onKeyPress={handleKeyPress}
        />
        <button className="send-button" onClick={handleSend}>
          Send
        </button>
      </div>
    </div>
  );
}

export default ChatArea;
