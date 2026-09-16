import React from "react";
import { Box, Typography } from "@mui/material";
import type { ChatAttachment, MessageType } from "../../types/chat";
import { formatBytes, getAttachmentDisplayUrl } from "./mediaUtils";

interface InlineVideoProps {
  attachment: ChatAttachment | undefined;
  messageType: MessageType;
}

function InlineVideo({ attachment, messageType }: InlineVideoProps) {
  const videoUrl = getAttachmentDisplayUrl(messageType, attachment);
  if (!attachment) {
    return null;
  }

  return (
    <div className="inline-video-wrapper">
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
    </div>
  );
}

export default InlineVideo;
