import React, { useEffect, useCallback } from "react";
import { createPortal } from "react-dom";
import { Box, IconButton, Typography } from "@mui/material";
import CloseIcon from "@mui/icons-material/Close";
import ChevronLeftIcon from "@mui/icons-material/ChevronLeft";
import ChevronRightIcon from "@mui/icons-material/ChevronRight";
import "./ImagePreview.css";

export interface ImagePreviewItem {
  url: string;
  alt: string;
  originalFilename?: string | null;
}

interface ImagePreviewProps {
  open: boolean;
  images: ImagePreviewItem[];
  currentIndex: number;
  onClose: () => void;
  onCurrentIndexChange: (index: number) => void;
}

function ImagePreview({
  open,
  images,
  currentIndex,
  onClose,
  onCurrentIndexChange,
}: ImagePreviewProps) {
  const hasMultiple = images.length > 1;
  const safeIndex = images.length
    ? Math.min(Math.max(currentIndex, 0), images.length - 1)
    : 0;
  const currentImage = images[safeIndex] || null;

  const showPrevious = useCallback(() => {
    if (!hasMultiple) {
      return;
    }
    onCurrentIndexChange(safeIndex === 0 ? images.length - 1 : safeIndex - 1);
  }, [hasMultiple, images.length, onCurrentIndexChange, safeIndex]);

  const showNext = useCallback(() => {
    if (!hasMultiple) {
      return;
    }
    onCurrentIndexChange(safeIndex === images.length - 1 ? 0 : safeIndex + 1);
  }, [hasMultiple, images.length, onCurrentIndexChange, safeIndex]);

  useEffect(() => {
    if (!open) {
      return undefined;
    }

    const previousOverflow = document.body.style.overflow;
    document.body.style.overflow = "hidden";

    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        event.preventDefault();
        onClose();
        return;
      }
      if (event.key === "ArrowLeft") {
        event.preventDefault();
        showPrevious();
        return;
      }
      if (event.key === "ArrowRight") {
        event.preventDefault();
        showNext();
      }
    };

    window.addEventListener("keydown", handleKeyDown);
    return () => {
      document.body.style.overflow = previousOverflow;
      window.removeEventListener("keydown", handleKeyDown);
    };
  }, [open, onClose, showNext, showPrevious]);

  if (!open || !currentImage) {
    return null;
  }

  return createPortal(
    <div
      className="image-preview-wrapper"
      role="dialog"
      aria-modal="true"
      aria-label="Image preview"
      onClick={onClose}
    >
      <IconButton
        className="image-preview-close-button"
        onClick={onClose}
        title="Close"
        aria-label="Close"
        size="large"
        sx={{ position: "fixed", top: 16, right: 16, left: "auto", bottom: "auto" }}
      >
        <CloseIcon />
      </IconButton>

      {hasMultiple ? (
        <IconButton
          className="image-preview-nav-button image-preview-prev-button"
          onClick={(event) => {
            event.stopPropagation();
            showPrevious();
          }}
          title="Previous image"
          aria-label="Previous image"
          size="large"
          sx={{ position: "fixed", top: "50%", left: 16, right: "auto", transform: "translateY(-50%)" }}
        >
          <ChevronLeftIcon fontSize="large" />
        </IconButton>
      ) : null}

      {hasMultiple ? (
        <IconButton
          className="image-preview-nav-button image-preview-next-button"
          onClick={(event) => {
            event.stopPropagation();
            showNext();
          }}
          title="Next image"
          aria-label="Next image"
          size="large"
          sx={{ position: "fixed", top: "50%", right: 16, left: "auto", transform: "translateY(-50%)" }}
        >
          <ChevronRightIcon fontSize="large" />
        </IconButton>
      ) : null}

      <Box className="image-preview-content">
        <img
          src={currentImage.url}
          alt={currentImage.alt}
          className="image-preview-image"
          onClick={(event) => event.stopPropagation()}
        />
        {currentImage.originalFilename ? (
          <Typography variant="body2" className="image-preview-filename" title={currentImage.originalFilename}>
            {currentImage.originalFilename}
          </Typography>
        ) : null}
        {hasMultiple ? (
          <Typography variant="caption" className="image-preview-counter">
            {safeIndex + 1} / {images.length}
          </Typography>
        ) : null}
      </Box>
    </div>,
    document.body,
  );
}

export default ImagePreview;
