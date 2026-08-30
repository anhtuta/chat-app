import React, { useMemo, useState } from "react";
import { Box, Typography } from "@mui/material";
import type { ChatAttachment } from "../../types/chat";
import { getAttachmentDisplayUrl, MESSAGE_TYPES } from "./mediaUtils";
import ImagePreview, { type ImagePreviewItem } from "./ImagePreview";

interface ImageGalleryProps {
  attachments: ChatAttachment[];
}

function resolvePreviewUrl(attachment: ChatAttachment): string | null {
  return (
    attachment.contentUrl
    || getAttachmentDisplayUrl(MESSAGE_TYPES.IMAGE, attachment)
    || null
  );
}

function ImageGallery({ attachments }: ImageGalleryProps) {
  const [previewIndex, setPreviewIndex] = useState<number | null>(null);

  const previewImages = useMemo(() => {
    const items: Array<ImagePreviewItem & { sourceIndex: number }> = [];
    attachments.forEach((attachment, index) => {
      const url = resolvePreviewUrl(attachment);
      if (!url) {
        return;
      }
      items.push({
        url,
        alt: attachment.originalFilename || `Image ${index + 1}`,
        originalFilename: attachment.originalFilename || null,
        sourceIndex: index,
      });
    });
    return items;
  }, [attachments]);

  const openPreview = (attachmentIndex: number) => {
    const previewPosition = previewImages.findIndex((item) => item.sourceIndex === attachmentIndex);
    if (previewPosition < 0) {
      return;
    }
    setPreviewIndex(previewPosition);
  };

  return (
    <div className="image-gallery-wrapper">
      <Box className={`chat-message-image-gallery ${attachments.length > 1 ? "multi" : "single"}`}>
        {attachments.map((attachment, index) => {
          const imageUrl = getAttachmentDisplayUrl(MESSAGE_TYPES.IMAGE, attachment);
          const canPreview = Boolean(resolvePreviewUrl(attachment));

          return (
            <button
              key={attachment.id || `${attachment.originalFilename}-${index}`}
              type="button"
              className="chat-message-image-link"
              onClick={() => openPreview(index)}
              disabled={!canPreview}
              title={canPreview ? "View image" : "Image unavailable"}
              aria-label={canPreview ? "View image" : "Image unavailable"}
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
            </button>
          );
        })}
      </Box>

      <ImagePreview
        open={previewIndex !== null}
        images={previewImages}
        currentIndex={previewIndex ?? 0}
        onClose={() => setPreviewIndex(null)}
        onCurrentIndexChange={setPreviewIndex}
      />
    </div>
  );
}

export default ImageGallery;
