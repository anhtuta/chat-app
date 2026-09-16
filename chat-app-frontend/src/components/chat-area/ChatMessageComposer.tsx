import React, { useRef, useState } from "react";
import {
  Box,
  TextField,
  Button,
  Stack,
  InputAdornment,
  IconButton,
  Typography,
} from "@mui/material";
import AttachFileIcon from "@mui/icons-material/AttachFile";
import CloseIcon from "@mui/icons-material/Close";
import SendIcon from "@mui/icons-material/Send";
import type { PendingChatAttachment } from "../../types/chat";
import { formatBytes } from "./mediaUtils";
import "./ChatMessageComposer.css";

interface ChatMessageComposerProps {
  onSendMessage: (content: string) => void;
  onSendMedia?: () => void;
  selectedMedia: PendingChatAttachment[];
  onSelectFiles: (files: FileList | null) => void;
  onRemoveSelectedMedia: (localId: string) => void;
  onClearSelectedMedia: () => void;
  mediaError?: string | null;
}

function ChatMessageComposer({
  onSendMessage,
  onSendMedia,
  selectedMedia,
  onSelectFiles,
  onRemoveSelectedMedia,
  onClearSelectedMedia,
  mediaError,
}: ChatMessageComposerProps) {
  const fileInputRef = useRef<HTMLInputElement>(null);
  const [messageInput, setMessageInput] = useState("");
  const hasSelectedMedia = selectedMedia.length > 0;
  const hasText = Boolean(messageInput.trim());
  const sendButtonLabel = hasSelectedMedia ? "Upload" : "Send";
  const disableSend = (!hasText && !hasSelectedMedia) || (hasSelectedMedia && hasText);

  const handleFileInputChange = (event: React.ChangeEvent<HTMLInputElement>) => {
    onSelectFiles(event.target.files);
    event.target.value = "";
  };

  const handleSend = () => {
    if (hasSelectedMedia) {
      if (hasText) {
        return;
      }
      onSendMedia?.();
      return;
    }

    if (hasText) {
      onSendMessage(messageInput.trim());
      setMessageInput("");
    }
  };

  const handleKeyPress = (event: React.KeyboardEvent<HTMLDivElement>) => {
    if (event.key === "Enter" && !event.shiftKey) {
      event.preventDefault();
      handleSend();
    }
  };

  return (
    <div className="chat-message-composer-wrapper">
      <Box className="chat-message-composer-container">
        <input
          ref={fileInputRef}
          type="file"
          multiple
          className="chat-message-file-input"
          onChange={handleFileInputChange}
        />
        {(hasSelectedMedia || mediaError) && (
          <Box className="chat-message-media-draft-panel">
            {hasSelectedMedia && (
              <>
                <Box className="chat-message-media-draft-header">
                  <Typography variant="subtitle2" className="chat-message-media-draft-title">
                    Ready to send
                  </Typography>
                  <Button size="small" onClick={onClearSelectedMedia}>
                    Clear all
                  </Button>
                </Box>
                <Box className="chat-message-media-draft-list">
                  {selectedMedia.map((attachment) => (
                    <Box key={attachment.localId} className="chat-message-media-draft-card">
                      {attachment.localPreviewUrl ? (
                        attachment.mimeType.startsWith("image/") ? (
                          <img
                            src={attachment.localPreviewUrl}
                            alt={attachment.originalFilename}
                            className="chat-message-media-draft-preview"
                          />
                        ) : attachment.mimeType.startsWith("video/") ? (
                          <video
                            src={attachment.localPreviewUrl}
                            className="chat-message-media-draft-preview"
                            muted
                          />
                        ) : (
                          <audio
                            src={attachment.localPreviewUrl}
                            className="chat-message-media-draft-audio"
                            controls
                          />
                        )
                      ) : (
                        <Box className="chat-message-media-draft-fallback">
                          {attachment.originalFilename.slice(0, 1).toUpperCase()}
                        </Box>
                      )}
                      <Box className="chat-message-media-draft-meta">
                        <Typography variant="body2" className="chat-message-media-draft-name">
                          {attachment.originalFilename}
                        </Typography>
                        <Typography variant="caption" className="chat-message-media-draft-size">
                          {formatBytes(attachment.sizeBytes)}
                        </Typography>
                      </Box>
                      <IconButton
                        size="small"
                        className="chat-message-media-draft-remove"
                        onClick={() => onRemoveSelectedMedia(attachment.localId)}
                        title={`Remove ${attachment.originalFilename}`}
                        aria-label={`Remove ${attachment.originalFilename}`}
                      >
                        <CloseIcon fontSize="small" />
                      </IconButton>
                    </Box>
                  ))}
                </Box>
              </>
            )}
            {mediaError && (
              <Typography variant="caption" className="chat-message-media-error">
                {mediaError}
              </Typography>
            )}
            {hasSelectedMedia && hasText && (
              <Typography variant="caption" className="chat-message-media-error">
                Media messages are sent without text for now. Clear the text box or send the text separately.
              </Typography>
            )}
          </Box>
        )}
        <Stack direction="row" spacing={1}>
          <Button
            className="chat-message-attach-button"
            variant="outlined"
            onClick={() => fileInputRef.current?.click()}
            startIcon={<AttachFileIcon />}
          >
            Attach
          </Button>
          <TextField
            className="chat-message-input"
            fullWidth
            placeholder={hasSelectedMedia ? "Media message ready to upload" : "Type a message..."}
            value={messageInput}
            onChange={(event) => setMessageInput(event.target.value)}
            onKeyDown={handleKeyPress}
            variant="outlined"
            size="small"
            multiline
            maxRows={3}
            InputProps={{
              endAdornment: (
                <InputAdornment position="end">
                  <Button
                    onClick={handleSend}
                    disabled={disableSend}
                    startIcon={<SendIcon />}
                    title={sendButtonLabel}
                    aria-label={sendButtonLabel}
                    sx={{ minWidth: "auto" }}
                  />
                </InputAdornment>
              ),
            }}
          />
          <Button
            className="chat-send-button"
            variant="contained"
            onClick={handleSend}
            disabled={disableSend}
            endIcon={<SendIcon />}
          >
            {sendButtonLabel}
          </Button>
        </Stack>
      </Box>
    </div>
  );
}

export default ChatMessageComposer;
