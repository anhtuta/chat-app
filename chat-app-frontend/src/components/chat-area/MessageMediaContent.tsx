import React from "react";
import { Box, Typography } from "@mui/material";
import { getProcessingIndicator, MESSAGE_TYPES } from "./mediaUtils";
import type { DisplayChatMessage } from "./displayChatMessage";
import ImageGallery from "./ImageGallery";
import InlineVideo from "./InlineVideo";
import InlineAudio from "./InlineAudio";
import FileAttachmentCard from "./FileAttachmentCard";
import LocalUploadStatus from "./LocalUploadStatus";

interface MessageMediaContentProps {
  message: DisplayChatMessage;
  onRetryPendingMessage?: (localId: string) => void;
  onCancelPendingMessage?: (localId: string) => void;
  onDismissPendingMessage?: (localId: string) => void;
}

function MessageMediaContent({
  message,
  onRetryPendingMessage,
  onCancelPendingMessage,
  onDismissPendingMessage,
}: MessageMediaContentProps) {
  const messageType = message.messageType || MESSAGE_TYPES.TEXT;
  const attachments = Array.isArray(message.attachments) ? message.attachments : [];
  const localUploadState = message.localUploadState || null;
  const processingIndicator = getProcessingIndicator(message);

  return (
    <div className="message-media-content-wrapper">
      <Box className="chat-message-media-block">
        {messageType === MESSAGE_TYPES.IMAGE && (
          <ImageGallery attachments={attachments} />
        )}
        {messageType === MESSAGE_TYPES.VIDEO && (
          <InlineVideo attachment={attachments[0]} messageType={messageType} />
        )}
        {messageType === MESSAGE_TYPES.AUDIO && (
          <InlineAudio attachment={attachments[0]} messageType={messageType} />
        )}
        {messageType === MESSAGE_TYPES.FILE && (
          <FileAttachmentCard attachment={attachments[0]} />
        )}
        {processingIndicator && (
          <Box className={`chat-message-processing-indicator ${processingIndicator.tone}`}>
            <Typography variant="caption" className="chat-message-processing-title">
              {processingIndicator.label}
            </Typography>
            <Typography variant="caption" className="chat-message-processing-copy">
              {processingIndicator.description}
            </Typography>
          </Box>
        )}
        {localUploadState && (
          <LocalUploadStatus
            message={message}
            localUploadState={localUploadState}
            onRetryPendingMessage={onRetryPendingMessage}
            onCancelPendingMessage={onCancelPendingMessage}
            onDismissPendingMessage={onDismissPendingMessage}
          />
        )}
      </Box>
    </div>
  );
}

export default MessageMediaContent;
