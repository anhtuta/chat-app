import React from "react";
import { Box, Paper, Typography, CircularProgress } from "@mui/material";
import "./ChatMessageList.css";

function formatRelativeTime(timestamp) {
  if (!timestamp) {
    return "just now";
  }

  const targetTime = new Date(timestamp).getTime();

  if (Number.isNaN(targetTime)) {
    return "just now";
  }

  const diffInSeconds = Math.max(0, Math.floor((Date.now() - targetTime) / 1000));

  if (diffInSeconds < 60) {
    return "just now";
  }

  const units = [
    { label: "y", seconds: 60 * 60 * 24 * 365 },
    { label: "mo", seconds: 60 * 60 * 24 * 30 },
    { label: "d", seconds: 60 * 60 * 24 },
    { label: "h", seconds: 60 * 60 },
    { label: "min", seconds: 60 },
  ];

  for (const unit of units) {
    if (diffInSeconds >= unit.seconds) {
      const value = Math.floor(diffInSeconds / unit.seconds);
      return `${value}${unit.label} ago`;
    }
  }

  return "just now";
}

function formatAbsoluteTimeVi(timestamp) {
  if (!timestamp) {
    return "";
  }

  const date = new Date(timestamp);

  if (Number.isNaN(date.getTime())) {
    return "";
  }

  // E.g. "29/04/2026, 14:30"
  return new Intl.DateTimeFormat("en-GB", {
    dateStyle: "short",
    timeStyle: "short",
  }).format(date);
}

function formatMessage(message, username) {
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
    relativeTimestamp: formatRelativeTime(message.timestamp),
    absoluteTimestamp: formatAbsoluteTimeVi(message.timestamp),
  };
}

function ChatMessageList({
  chatMessagesRef,
  messagesEndRef,
  messages,
  username,
  isLoading,
  isLoadingOlder,
  onScroll,
}) {
  return (
    <div className="chat-message-list-wrapper">
      {isLoading && (
        <Box className="chat-message-list-loading-header">
          <CircularProgress size={24} />
          Loading previous messages
        </Box>
      )}

      <Box className="chat-message-list-container" ref={chatMessagesRef} onScroll={onScroll}>
        {isLoadingOlder && (
          <Box className="chat-message-list-loading-older-container">
            <CircularProgress size={24} />
            <Typography variant="body2" className="chat-message-list-loading-older-text">
              Loading older messages...
            </Typography>
          </Box>
        )}

        {messages.map((message, index) => {
          const formatted = formatMessage(message, username);

          if (formatted.type === "system") {
            const systemClass = formatted.isDisconnected
              ? "disconnected"
              : formatted.isConnected
                ? "connected"
                : "default";
            return (
              <Box key={index} className={`chat-message-system-message ${systemClass}`}>
                <Typography variant="caption" className={`chat-message-system-text ${systemClass}`}>
                  {formatted.content}
                </Typography>
              </Box>
            );
          }

          const isOwnMessage = formatted.type === "sent";

          return (
            <Box
              key={message.id || index}
              className={`chat-message-bubble-wrapper ${isOwnMessage ? "own" : "other"}`}
            >
              <Paper className={`chat-message-bubble ${isOwnMessage ? "own" : "other"}`}>
                <Typography
                  variant="caption"
                  className={`chat-message-sender-info ${isOwnMessage ? "own" : ""}`}
                >
                  <Box component="span" className="chat-message-sender-name">
                    {formatted.displayName}
                  </Box>
                  <Box
                    component="span"
                    className="chat-message-timestamp"
                    title={formatted.absoluteTimestamp}
                  >
                    {formatted.relativeTimestamp}
                  </Box>
                </Typography>
                <Typography variant="body2" className="chat-message-text">
                  {formatted.content}
                </Typography>
              </Paper>
            </Box>
          );
        })}

        <div ref={messagesEndRef} />
      </Box>
    </div>
  );
}

export default ChatMessageList;