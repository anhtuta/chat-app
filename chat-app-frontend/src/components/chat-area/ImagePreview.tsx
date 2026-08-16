import React, { useEffect, useCallback, useRef } from "react";
import { createPortal } from "react-dom";
import { Box, IconButton, Typography } from "@mui/material";
import CloseIcon from "@mui/icons-material/Close";
import ChevronLeftIcon from "@mui/icons-material/ChevronLeft";
import ChevronRightIcon from "@mui/icons-material/ChevronRight";
import "./ImagePreview.css";

const FOCUSABLE_ELEMENTS_SELECTOR =
  'button:not([disabled]), [href], input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])';

function getFocusableElements(container: HTMLElement): HTMLElement[] {
  return Array.from(container.querySelectorAll<HTMLElement>(FOCUSABLE_ELEMENTS_SELECTOR));
}

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
  const dialogRef = useRef<HTMLDivElement>(null);
  const closeButtonRef = useRef<HTMLButtonElement>(null);
  const previouslyFocusedElementRef = useRef<HTMLElement | null>(null);
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

    previouslyFocusedElementRef.current =
      document.activeElement instanceof HTMLElement ? document.activeElement : null;

    const previousOverflow = document.body.style.overflow;
    document.body.style.overflow = "hidden";

    const focusFrameId = requestAnimationFrame(() => {
      closeButtonRef.current?.focus();
    });

    const trapTabNavigation = (event: KeyboardEvent) => {
      const dialog = dialogRef.current;
      if (!dialog || event.key !== "Tab") {
        return;
      }

      const focusableElements = getFocusableElements(dialog);
      if (focusableElements.length === 0) {
        event.preventDefault();
        return;
      }

      const firstElement = focusableElements[0];
      const lastElement = focusableElements[focusableElements.length - 1];
      const activeElement = document.activeElement;
      const activeIsInDialog = activeElement instanceof Node && dialog.contains(activeElement);

      if (event.shiftKey) {
        if (!activeIsInDialog || activeElement === firstElement) {
          event.preventDefault();
          lastElement.focus();
        }
        return;
      }

      if (!activeIsInDialog || activeElement === lastElement) {
        event.preventDefault();
        firstElement.focus();
      }
    };

    const keepFocusInsideDialog = (event: FocusEvent) => {
      const dialog = dialogRef.current;
      const target = event.target;
      if (!dialog || !(target instanceof Node) || dialog.contains(target)) {
        return;
      }

      const focusableElements = getFocusableElements(dialog);
      focusableElements[0]?.focus();
    };

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
        return;
      }

      trapTabNavigation(event);
    };

    window.addEventListener("keydown", handleKeyDown);
    document.addEventListener("focusin", keepFocusInsideDialog);
    return () => {
      cancelAnimationFrame(focusFrameId);
      document.body.style.overflow = previousOverflow;
      window.removeEventListener("keydown", handleKeyDown);
      document.removeEventListener("focusin", keepFocusInsideDialog);

      const elementToRestore = previouslyFocusedElementRef.current;
      if (elementToRestore && document.contains(elementToRestore)) {
        elementToRestore.focus();
      }
    };
  }, [open, onClose, showNext, showPrevious]);

  if (!open || !currentImage) {
    return null;
  }

  return createPortal(
    <div
      ref={dialogRef}
      className="image-preview-wrapper"
      role="dialog"
      aria-modal="true"
      aria-label="Image preview"
      tabIndex={-1}
      onClick={onClose}
    >
      <IconButton
        ref={closeButtonRef}
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
