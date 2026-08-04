import React from "react";
import { Box, Typography } from "@mui/material";
import type { ChatAttachment, MessageType } from "../../types/chat";
import { formatBytes, getAttachmentDisplayUrl } from "./mediaUtils";

interface InlineAudioProps {
  attachment: ChatAttachment | undefined;
  messageType: MessageType;
}

function InlineAudio({ attachment, messageType }: InlineAudioProps) {
  const audioUrl = getAttachmentDisplayUrl(messageType, attachment);
  if (!attachment) {
    return null;
  }

  return (
    <div className="inline-audio-wrapper">
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
    </div>
  );
}

export default InlineAudio;
