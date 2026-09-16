import React from "react";
import { Box, Typography } from "@mui/material";
import type { ChatAttachment } from "../../types/chat";
import { formatBytes } from "./mediaUtils";

interface FileAttachmentCardProps {
  attachment: ChatAttachment | undefined;
}

function FileAttachmentCard({ attachment }: FileAttachmentCardProps) {
  if (!attachment) {
    return null;
  }

  return (
    <div className="file-attachment-card-wrapper">
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
    </div>
  );
}

export default FileAttachmentCard;
