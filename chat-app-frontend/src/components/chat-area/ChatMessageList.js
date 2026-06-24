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

function MessageMediaContent({
  message,
  formatted,
  onRetryPendingMessage,
  onCancelPendingMessage,
  onDismissPendingMessage,
}) {
  return (
    <Box className="chat-message-media-block">
      {formatted.messageType === MESSAGE_TYPES.IMAGE && (
        <ImageGallery attachments={formatted.attachments} />
      )}
      {formatted.messageType === MESSAGE_TYPES.VIDEO && (
        <InlineVideo attachment={formatted.attachments[0]} messageType={formatted.messageType} />
      )}
      {formatted.messageType === MESSAGE_TYPES.AUDIO && (
        <InlineAudio attachment={formatted.attachments[0]} messageType={formatted.messageType} />
      )}
      {formatted.messageType === MESSAGE_TYPES.FILE && (
        <FileAttachmentCard attachment={formatted.attachments[0]} />
      )}
      {formatted.processingIndicator && (
        <Box className={`chat-message-processing-indicator ${formatted.processingIndicator.tone}`}>
          <Typography variant="caption" className="chat-message-processing-title">
            {formatted.processingIndicator.label}
          </Typography>
          <Typography variant="caption" className="chat-message-processing-copy">
            {formatted.processingIndicator.description}
          </Typography>
        </Box>
      )}
      {formatted.localUploadState && (
        <LocalUploadStatus
          message={message}
          localUploadState={formatted.localUploadState}
          onRetryPendingMessage={onRetryPendingMessage}
          onCancelPendingMessage={onCancelPendingMessage}
          onDismissPendingMessage={onDismissPendingMessage}
        />
      )}
    </Box>
  );
}

function ImageGallery({ attachments }) {
  return (
    <Box className={`chat-message-image-gallery ${attachments.length > 1 ? "multi" : "single"}`}>
      {attachments.map((attachment, index) => {
        const imageUrl = getAttachmentDisplayUrl(MESSAGE_TYPES.IMAGE, attachment);

        return (
          <a
            key={attachment.id || `${attachment.originalFilename}-${index}`}
            href={attachment.contentUrl || imageUrl || "#"}
            target="_blank"
            rel="noreferrer"
            className="chat-message-image-link"
          >
            {imageUrl ? (
              <img
                src={imageUrl}
                alt={attachment.originalFilename || `Image ${index + 1}`}
                className="chat-message-image"
                loading="lazy"
              />
            ) : (
              <Box className="chat-message-image-fallback">
                <Typography variant="caption">{attachment.originalFilename || "Image unavailable"}</Typography>
              </Box>
            )}
          </a>
        );
      })}
    </Box>
  );
}

function InlineVideo({ attachment, messageType }) {
  const videoUrl = getAttachmentDisplayUrl(messageType, attachment);
  if (!attachment) {
    return null;
  }

  return (
    <Box className="chat-message-video-card">
      {videoUrl ? (
        <video
          className="chat-message-video"
          controls
          preload="metadata"
          src={videoUrl}
        />
      ) : (
        <Box className="chat-message-media-fallback">
          <Typography variant="body2">Video preview unavailable</Typography>
        </Box>
      )}
      <Typography variant="caption" className="chat-message-attachment-meta">
        {attachment.originalFilename} {attachment.sizeBytes ? `• ${formatBytes(attachment.sizeBytes)}` : ""}
      </Typography>
    </Box>
  );
}

function InlineAudio({ attachment, messageType }) {
  const audioUrl = getAttachmentDisplayUrl(messageType, attachment);
  if (!attachment) {
    return null;
  }

  return (
    <Box className="chat-message-audio-card">
      {audioUrl ? (
        <audio className="chat-message-audio" controls preload="metadata" src={audioUrl}>
          <track kind="captions" />
        </audio>
      ) : (
        <Box className="chat-message-media-fallback">
          <Typography variant="body2">Audio preview unavailable</Typography>
        </Box>
      )}
      <Typography variant="caption" className="chat-message-attachment-meta">
        {attachment.originalFilename} {attachment.sizeBytes ? `• ${formatBytes(attachment.sizeBytes)}` : ""}
      </Typography>
    </Box>
  );
}

function FileAttachmentCard({ attachment }) {
  if (!attachment) {
    return null;
  }

  return (
    <Box className="chat-message-file-card">
      <Box className="chat-message-file-meta">
        <Typography variant="body2" className="chat-message-file-name">
          {attachment.originalFilename || "Attachment"}
        </Typography>
        <Typography variant="caption" className="chat-message-attachment-meta">
          {formatBytes(attachment.sizeBytes)}
        </Typography>
      </Box>
      <Box className="chat-message-file-actions">
        {attachment.contentUrl && (
          <>
            <a href={attachment.contentUrl} target="_blank" rel="noreferrer" className="chat-message-file-link">
              Open
            </a>
            <a href={attachment.contentUrl} download className="chat-message-file-link">
              Download
            </a>
          </>
        )}
      </Box>
    </Box>
  );
}

function LocalUploadStatus({
  message,
  localUploadState,
  onRetryPendingMessage,
  onCancelPendingMessage,
  onDismissPendingMessage,
}) {
  const statusCopy = getLocalUploadStatusCopy(localUploadState.status);
  const isUploading =
    localUploadState.status === LOCAL_UPLOAD_STATUSES.UPLOAD_IN_PROGRESS
    || localUploadState.status === LOCAL_UPLOAD_STATUSES.FINALIZING;
  const showRetry =
    localUploadState.status === LOCAL_UPLOAD_STATUSES.UPLOAD_FAILED
    || localUploadState.status === LOCAL_UPLOAD_STATUSES.CANCELED;

  return (
    <Box className="chat-message-local-status">
      <Typography variant="caption" className="chat-message-local-status-title">
        {statusCopy.title}
      </Typography>
      <Typography variant="caption" className="chat-message-local-status-copy">
        {localUploadState.errorMessage || statusCopy.description}
      </Typography>
      {isUploading && (
        <>
          <LinearProgress
            variant="determinate"
            value={Math.max(0, Math.min(100, Number(localUploadState.progressPercent || 0)))}
            className="chat-message-local-progress"
          />
          <Typography variant="caption" className="chat-message-local-progress-copy">
            {Math.round(Number(localUploadState.progressPercent || 0))}%
          </Typography>
        </>
      )}
      <Box className="chat-message-local-actions">
        {showRetry && (
          <Button size="small" onClick={() => onRetryPendingMessage?.(message.localId)}>
            Retry
          </Button>
        )}
        {isUploading && (
          <Button size="small" onClick={() => onCancelPendingMessage?.(message.localId)}>
            Cancel
          </Button>
        )}
        {!isUploading && (
          <Button size="small" onClick={() => onDismissPendingMessage?.(message.localId)}>
            Dismiss
          </Button>
        )}
      </Box>
    </Box>
  );
}