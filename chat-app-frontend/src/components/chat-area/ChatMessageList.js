import React from "react";
import { Box, Paper, Typography, CircularProgress } from "@mui/material";

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

  return new Intl.DateTimeFormat("vi-VN", {
    dateStyle: "full",
    timeStyle: "medium",
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
        <Box sx={{ p: 2, textAlign: "center" }}>
          <CircularProgress size={24} sx={{ mr: 1 }} />
          Loading previous messages
        </Box>
      )}

      <Box
        ref={chatMessagesRef}
        onScroll={onScroll}
        sx={{
          flex: 1,
          overflowY: "auto",
          p: 2,
          backgroundColor: "var(--color-surface-muted)",
        }}
      >
        {isLoadingOlder && (
          <Box sx={{ textAlign: "center", py: 2 }}>
            <CircularProgress size={24} />
            <Typography variant="body2" color="textSecondary" sx={{ mt: 1 }}>
              Loading older messages...
            </Typography>
          </Box>
        )}

        {messages.map((message, index) => {
          const formatted = formatMessage(message, username);

          if (formatted.type === "system") {
            return (
              <Box
                key={index}
                sx={{
                  textAlign: "center",
                  py: 1.5,
                  px: 2,
                  my: 1,
                  backgroundColor: formatted.isDisconnected
                    ? "var(--color-status-error-soft)"
                    : formatted.isConnected
                      ? "var(--color-status-success-soft)"
                      : "var(--color-surface-soft)",
                  borderRadius: 1,
                }}
              >
                <Typography
                  variant="caption"
                  sx={{
                    color: formatted.isDisconnected
                      ? "var(--color-status-error-text)"
                      : formatted.isConnected
                        ? "var(--color-status-success-text)"
                        : "var(--color-text-secondary)",
                    fontWeight: 500,
                  }}
                >
                  {formatted.content}
                </Typography>
              </Box>
            );
          }

          const isOwnMessage = formatted.type === "sent";

          return (
            <Box
              key={message.id || index}
              sx={{
                display: "flex",
                justifyContent: isOwnMessage ? "flex-end" : "flex-start",
                mb: 1.5,
              }}
            >
              <Paper
                sx={{
                  maxWidth: "60%",
                  p: 1.5,
                  backgroundColor: isOwnMessage ? "var(--color-primary)" : "var(--color-message-received)",
                  color: isOwnMessage ? "var(--color-surface)" : "var(--color-text-primary)",
                  borderRadius: 2,
                }}
              >
                <Typography
                  variant="caption"
                  sx={{
                    display: "flex",
                    alignItems: "center",
                    justifyContent: "space-between",
                    gap: 1,
                    fontWeight: 600,
                    mb: 0.5,
                    opacity: isOwnMessage ? 0.9 : 1,
                  }}
                >
                  <Box component="span" sx={{ fontWeight: 600 }}>
                    {formatted.displayName}
                  </Box>
                  <Box
                    component="span"
                    title={formatted.absoluteTimestamp}
                    sx={{ fontWeight: 400, opacity: 0.8, cursor: "help" }}
                  >
                    {formatted.relativeTimestamp}
                  </Box>
                </Typography>
                <Typography variant="body2" sx={{ mb: 0.5 }}>
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