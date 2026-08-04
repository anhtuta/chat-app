import React from "react";
import { Box, CircularProgress, Typography } from "@mui/material";
import ChatMessageItem from "./ChatMessageItem";
import "./ChatMessageList.css";

function ChatMessageList({
  chatMessagesRef,
  messagesEndRef,
  messages,
  username,
  currentUserPermissions,
  isLoading,
  isLoadingOlder,
  onScroll,
  onRetryPendingMessage,
  onCancelPendingMessage,
  onDismissPendingMessage,
  onMessageModerated,
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

        {messages.map((message, index) => (
          <ChatMessageItem
            key={message.id || message.localId || index}
            message={message}
            username={username}
            currentUserPermissions={currentUserPermissions}
            onRetryPendingMessage={onRetryPendingMessage}
            onCancelPendingMessage={onCancelPendingMessage}
            onDismissPendingMessage={onDismissPendingMessage}
            onMessageModerated={onMessageModerated}
          />
        ))}

        <div ref={messagesEndRef} />
      </Box>
    </div>
  );
}

export default ChatMessageList;
