import React from "react";
import { Box, Paper, Typography, CircularProgress } from "@mui/material";

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
                    display: "block",
                    fontWeight: 600,
                    mb: 0.5,
                    opacity: isOwnMessage ? 0.9 : 1,
                  }}
                >
                  {formatted.displayName}
                </Typography>
                <Typography variant="body2" sx={{ mb: 0.5 }}>
                  {formatted.content}
                </Typography>
                <Typography
                  variant="caption"
                  sx={{
                    display: "block",
                    opacity: isOwnMessage ? 0.8 : 0.7,
                  }}
                >
                  {new Date(formatted.timestamp).toLocaleTimeString([], {
                    hour: "2-digit",
                    minute: "2-digit",
                  })}{" "}
                  {new Date(formatted.timestamp).toLocaleDateString()}
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