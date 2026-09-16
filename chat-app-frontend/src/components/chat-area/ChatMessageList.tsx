import React from "react";
import { Box, Button, CircularProgress, Typography } from "@mui/material";
import type { ChatMessage } from "../../types/chat";
import ChatMessageItem from "./ChatMessageItem";
import { type DisplayChatMessage, getPendingLocalId } from "./displayChatMessage";
import "./ChatMessageList.css";

interface ChatMessageListProps {
  chatMessagesRef: React.RefObject<HTMLDivElement | null>;
  messagesEndRef: React.RefObject<HTMLDivElement | null>;
  messages: DisplayChatMessage[];
  username: string | null;
  currentUserPermissions?: string[] | null;
  isLoading: boolean;
  isLoadingOlder: boolean;
  onScroll: (event: React.UIEvent<HTMLDivElement>) => void;
  showLoadOlderFallback: boolean;
  onLoadOlderFallback: () => void;
  onRetryPendingMessage?: (localId: string) => void;
  onCancelPendingMessage?: (localId: string) => void;
  onDismissPendingMessage?: (localId: string) => void;
  onMessageModerated?: (updatedMessage: ChatMessage) => void;
}

function ChatMessageList({
  chatMessagesRef,
  messagesEndRef,
  messages,
  username,
  currentUserPermissions,
  isLoading,
  isLoadingOlder,
  onScroll,
  showLoadOlderFallback,
  onLoadOlderFallback,
  onRetryPendingMessage,
  onCancelPendingMessage,
  onDismissPendingMessage,
  onMessageModerated,
}: ChatMessageListProps) {
  return (
    <div className="chat-message-list-wrapper">
      {isLoading && (
        <Box className="chat-message-list-loading-header">
          <CircularProgress size={24} />
          Loading previous messages
        </Box>
      )}

      <Box className="chat-message-list-container" ref={chatMessagesRef} onScroll={onScroll}>
        {showLoadOlderFallback && !isLoadingOlder && (
          <Box className="chat-message-list-load-older-action">
            <Button
              type="button"
              variant="outlined"
              size="small"
              title="Load older messages"
              aria-label="Load older messages"
              onClick={onLoadOlderFallback}
            >
              Load older messages
            </Button>
          </Box>
        )}

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
            key={message.id || getPendingLocalId(message) || index}
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
