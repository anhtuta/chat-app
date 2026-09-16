import React from "react";
import { Box, Typography } from "@mui/material";
import type {
  ChatAttachment,
  LocalUploadState,
  MessageType,
  ProcessingIndicator,
} from "../../types/chat";
import { MESSAGE_TYPES } from "./mediaUtils";
import type { DisplayChatMessage } from "./displayChatMessage";
import ImageGallery from "./ImageGallery";
import InlineVideo from "./InlineVideo";
import InlineAudio from "./InlineAudio";
import FileAttachmentCard from "./FileAttachmentCard";
import LocalUploadStatus from "./LocalUploadStatus";

export interface MessageMediaFormattedFields {
  messageType: MessageType;
  attachments: ChatAttachment[];
  localUploadState: LocalUploadState | null;
  processingIndicator: ProcessingIndicator | null;
}

interface MessageMediaContentProps {
  message: DisplayChatMessage;
  formatted: MessageMediaFormattedFields;
  onRetryPendingMessage?: (localId: string) => void;
  onCancelPendingMessage?: (localId: string) => void;
  onDismissPendingMessage?: (localId: string) => void;
}

function MessageMediaContent({
  message,
  formatted,
  onRetryPendingMessage,
  onCancelPendingMessage,
  onDismissPendingMessage,
}: MessageMediaContentProps) {
  return (
    <div className="message-media-content-wrapper">
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
    </div>
  );
}

export default MessageMediaContent;
