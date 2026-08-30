import React from "react";
import { Box, Button, LinearProgress, Typography } from "@mui/material";
import type { LocalUploadState } from "../../types/chat";
import { getLocalUploadStatusCopy, LOCAL_UPLOAD_STATUSES } from "./mediaUtils";
import { getPendingLocalId, type DisplayChatMessage } from "./displayChatMessage";

interface LocalUploadStatusProps {
  message: DisplayChatMessage;
  localUploadState: LocalUploadState;
  onRetryPendingMessage?: (localId: string) => void;
  onCancelPendingMessage?: (localId: string) => void;
  onDismissPendingMessage?: (localId: string) => void;
}

function LocalUploadStatus({
  message,
  localUploadState,
  onRetryPendingMessage,
  onCancelPendingMessage,
  onDismissPendingMessage,
}: LocalUploadStatusProps) {
  const statusCopy = getLocalUploadStatusCopy(localUploadState.status);
  const isUploading =
    localUploadState.status === LOCAL_UPLOAD_STATUSES.UPLOAD_IN_PROGRESS
    || localUploadState.status === LOCAL_UPLOAD_STATUSES.FINALIZING;
  const showRetry =
    localUploadState.status === LOCAL_UPLOAD_STATUSES.UPLOAD_FAILED
    || localUploadState.status === LOCAL_UPLOAD_STATUSES.CANCELED;
  const localId = getPendingLocalId(message);

  return (
    <div className="local-upload-status-wrapper">
      <Box className="chat-message-local-status">
        <Typography variant="caption" className="chat-message-local-status-title">
          {statusCopy.title}
        </Typography>
        <Typography variant="caption" className="chat-message-local-status-copy">
          {localUploadState.errorMessage || statusCopy.description}
        </Typography>
        {isUploading && (
          <>
            <LinearProgress
              variant="determinate"
              value={Math.max(0, Math.min(100, Number(localUploadState.progressPercent || 0)))}
              className="chat-message-local-progress"
            />
            <Typography variant="caption" className="chat-message-local-progress-copy">
              {Math.round(Number(localUploadState.progressPercent || 0))}%
            </Typography>
          </>
        )}
        <Box className="chat-message-local-actions">
          {showRetry && localId ? (
            <Button
              size="small"
              onClick={() => onRetryPendingMessage?.(localId)}
              title="Retry upload"
              aria-label="Retry upload"
            >
              Retry
            </Button>
          ) : null}
          {isUploading && localId ? (
            <Button
              size="small"
              onClick={() => onCancelPendingMessage?.(localId)}
              title="Cancel upload"
              aria-label="Cancel upload"
            >
              Cancel
            </Button>
          ) : null}
          {!isUploading && localId ? (
            <Button
              size="small"
              onClick={() => onDismissPendingMessage?.(localId)}
              title="Dismiss upload"
              aria-label="Dismiss upload"
            >
              Dismiss
            </Button>
          ) : null}
        </Box>
      </Box>
    </div>
  );
}

export default LocalUploadStatus;
