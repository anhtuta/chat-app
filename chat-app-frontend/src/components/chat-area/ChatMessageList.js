import React from "react";
import { Box, Paper, Typography, CircularProgress, Button, LinearProgress } from "@mui/material";
import "./ChatMessageList.css";
import {
  formatBytes,
  getAttachmentDisplayUrl,
  getLocalUploadStatusCopy,
  getProcessingIndicator,
  isMediaMessageType,
  LOCAL_UPLOAD_STATUSES,
  MESSAGE_TYPES,
} from "./mediaUtils";
import { formatAbsoluteTimeVi, formatRelativeTime } from "../../utils/dateUtils";
import { SYSTEM_EVENT_TYPES } from "../../constant/systemEventTypes";

function getDisplayUserName(user) {
  if (!user) {
    return "Someone";
  }
  return user.fullname || user.username || "Someone";
}

function formatStructuredSystemMessage(message) {
  const actorName = getDisplayUserName(message.systemEventActor);
  const subjectName = getDisplayUserName(message.user);

  switch (message.systemEventType) {
    case SYSTEM_EVENT_TYPES.USER_JOINED:
      return actorName === subjectName ? `${subjectName} has joined the group` : `${actorName} has added ${subjectName}`;
    case SYSTEM_EVENT_TYPES.USER_LEFT:
      return `${subjectName} has left the group`;
    case SYSTEM_EVENT_TYPES.USER_KICKED:
      return `${subjectName} has been kicked out of the group by ${actorName}`;
    case SYSTEM_EVENT_TYPES.USER_BANNED:
      return `${subjectName} has been banned by ${actorName}`;
    case SYSTEM_EVENT_TYPES.USER_UNBANNED:
      return `${subjectName} has been unbanned by ${actorName}`;
    case SYSTEM_EVENT_TYPES.USER_PROMOTED:
      return `${actorName} promoted ${subjectName}`;
    case SYSTEM_EVENT_TYPES.USER_DEMOTED:
      return `${actorName} demoted ${subjectName}`;
    case SYSTEM_EVENT_TYPES.LEADERSHIP_TRANSFERRED:
      return `${actorName} transferred leadership to ${subjectName}`;
    case SYSTEM_EVENT_TYPES.GROUP_NAME_UPDATED:
      return `${actorName} updated the group name`;
    case SYSTEM_EVENT_TYPES.GROUP_DESCRIPTION_UPDATED:
      return `${actorName} updated the group description`;
    case SYSTEM_EVENT_TYPES.GROUP_ARCHIVED:
      return `${actorName} archived the group`;
    default:
      return message.content || "System event";
  }
}

function formatMessage(message, username) {
  if (message.messageType === MESSAGE_TYPES.SYSTEM && message.systemEventType) {
    return {
      type: "system",
      content: formatStructuredSystemMessage(message),
      isDisconnected: false,
      isConnected: false,
    };
  }

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
    content: message.deletedAt ? "Message deleted" : message.content,
    timestamp: message.timestamp,
    relativeTimestamp: formatRelativeTime(message.timestamp),
    absoluteTimestamp: formatAbsoluteTimeVi(message.timestamp),
    isEdited: Boolean(message.updatedAt) && !message.deletedAt,
    messageType: message.messageType || MESSAGE_TYPES.TEXT,
    attachments: Array.isArray(message.attachments) ? message.attachments : [],
    localUploadState: message.localUploadState || null,
    processingIndicator: getProcessingIndicator(message),
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
  onRetryPendingMessage,
  onCancelPendingMessage,
  onDismissPendingMessage,
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
          // todo remove this. todo test this: while uploading a photo and updating progress of that message, it re-render all other messages? Evidence: while uploading a photo, these logs are printed constantly.
          if (message.attachments.length > 0) {
            console.log("formatted", formatted);
            console.log("message", message);
          }

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
                    {formatted.isEdited ? " (edited)" : ""}
                  </Box>
                </Typography>
                <Typography variant="body2" className="chat-message-text">
                  {formatted.content}
                </Typography>
                {isMediaMessageType(formatted.messageType) && (
                  <MessageMediaContent
                    message={message}
                    formatted={formatted}
                    onRetryPendingMessage={onRetryPendingMessage}
                    onCancelPendingMessage={onCancelPendingMessage}
                    onDismissPendingMessage={onDismissPendingMessage}
                  />
                )}
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
