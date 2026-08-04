import React from "react";
import { Box, Typography } from "@mui/material";
import type { ChatAttachment } from "../../types/chat";
import { getAttachmentDisplayUrl, MESSAGE_TYPES } from "./mediaUtils";

interface ImageGalleryProps {
  attachments: ChatAttachment[];
}

function ImageGallery({ attachments }: ImageGalleryProps) {
  return (
    <div className="image-gallery-wrapper">
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
    </div>
  );
}

export default ImageGallery;
