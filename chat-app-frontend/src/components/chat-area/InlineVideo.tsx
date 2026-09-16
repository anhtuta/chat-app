import React from "react";
import { Box, Typography } from "@mui/material";
import type { ChatAttachment, MessageType } from "../../types/chat";
import { formatBytes, formatDuration, getAttachmentDisplayUrl } from "./mediaUtils";

interface InlineVideoProps {
  attachment: ChatAttachment | undefined;
  messageType: MessageType;
}

function InlineVideo({ attachment, messageType }: InlineVideoProps) {
  const videoUrl = getAttachmentDisplayUrl(messageType, attachment);
  if (!attachment) {
    return null;
  }

  const metadataParts = [
    attachment.durationMs ? formatDuration(attachment.durationMs) : "",
    attachment.sizeBytes ? formatBytes(attachment.sizeBytes) : "",
  ].filter(Boolean);

  return (
    <div className="inline-video-wrapper">
      <Box className="chat-message-video-card">
        {videoUrl ? (
          <video
            className="chat-message-video"
            controls
            preload="metadata"
            poster={attachment.posterUrl || attachment.thumbnailUrl || undefined}
            src={videoUrl}
          />
        ) : (
          <Box className="chat-message-media-fallback">
            <Typography variant="body2">Video preview unavailable</Typography>
          </Box>
        )}
        <Typography variant="caption" className="chat-message-attachment-meta">
          {attachment.originalFilename}
          {metadataParts.length ? ` • ${metadataParts.join(" • ")}` : ""}
        </Typography>
      </Box>
    </div>
  );
}

export default InlineVideo;
