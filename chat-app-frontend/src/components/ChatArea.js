import React, { useState, useEffect, useRef } from "react";
import "./ChatArea.css";

function ChatArea({ chatName, messages, isLoading, isConnected, username, onSendMessage, onLogout }) {
  const [messageInput, setMessageInput] = useState("");
  const messagesEndRef = useRef(null);

  useEffect(() => {
    scrollToBottom();
  }, [messages]);

  const scrollToBottom = () => {
    messagesEndRef.current?.scrollIntoView({ behavior: "smooth" });
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
      <div className="chat-messages">
        {messages.map((message, index) => {
          const formatted = formatMessage(message);

          if (formatted.type === "system") {
            return (
              <div
                key={index}
                className={`message system ${formatted.isDisconnected ? "disconnected" : ""} ${
                  formatted.isConnected ? "connected" : ""
                }`}
              >
                <div className="message-content">{formatted.content}</div>
              </div>
            );
          }

          return (
            <div key={index} className={`message ${formatted.type}`}>
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
