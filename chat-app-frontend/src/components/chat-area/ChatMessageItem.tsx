import React, { useState } from "react";
import {
  Alert,
  Box,
  Button,
  CircularProgress,
  Dialog,
  DialogActions,
  DialogContent,
  DialogContentText,
  DialogTitle,
  IconButton,
  Menu,
  MenuItem,
  Paper,
  TextField,
  Typography,
} from "@mui/material";
import MoreVertIcon from "@mui/icons-material/MoreVert";
import { deleteMessage, updateMessage } from "../../services/api";
import { canDeleteMessage, canEditMessage } from "../../utils/messageModeration";
import { formatAbsoluteTimeVi, formatRelativeTime } from "../../utils/dateUtils";
import { SYSTEM_EVENT_TYPES } from "../../constant/systemEventTypes";
import type {
  ChatAttachment,
  ChatMessage,
  ChatUser,
  LocalUploadState,
  MessageType,
  ProcessingIndicator,
} from "../../types/chat";
import {
  getProcessingIndicator,
  isMediaMessageType,
  MESSAGE_TYPES,
} from "./mediaUtils";
import { type DisplayChatMessage } from "./displayChatMessage";
import MessageMediaContent from "./MessageMediaContent";

interface ChatMessageItemProps {
  message: DisplayChatMessage;
  username: string | null;
  currentUserPermissions?: string[] | null;
  onRetryPendingMessage?: (localId: string) => void;
  onCancelPendingMessage?: (localId: string) => void;
  onDismissPendingMessage?: (localId: string) => void;
  /** Notify parent with the API response so ChatPage can upsert the list + sidebar preview. */
  onMessageModerated?: (updatedMessage: ChatMessage) => void;
}

interface FormattedSystemMessage {
  type: "system";
  content: string;
  isDisconnected: boolean;
  isConnected: boolean;
}

interface FormattedChatBubble {
  type: "sent" | "received";
  displayName: string;
  content: string | null | undefined;
  timestamp: string | null | undefined;
  relativeTimestamp: string;
  absoluteTimestamp: string;
  isEdited: boolean;
  editedByLabel: string;
  messageType: MessageType;
  attachments: ChatAttachment[];
  localUploadState: LocalUploadState | null;
  processingIndicator: ProcessingIndicator | null;
}

type FormattedMessage = FormattedSystemMessage | FormattedChatBubble;

function getDisplayUserName(user: ChatUser | null | undefined): string {
  if (!user) {
    return "Someone";
  }
  return user.fullname || user.username || "Someone";
}

function formatStructuredSystemMessage(message: ChatMessage): string {
  const actorName = getDisplayUserName(message.systemEventActor);
  const subjectName = getDisplayUserName(message.user);

  switch (message.systemEventType) {
    case SYSTEM_EVENT_TYPES.USER_JOINED:
      return actorName === subjectName ? `${subjectName} has joined the group` : `${actorName} has added ${subjectName}`;
    case SYSTEM_EVENT_TYPES.USER_LEFT:
      return `${subjectName} has left the group`;
    case SYSTEM_EVENT_TYPES.USER_KICKED:
      return `${subjectName} has been kicked out of the group by ${actorName}`;
    case SYSTEM_EVENT_TYPES.USER_BANNED:
      return `${subjectName} has been banned by ${actorName}`;
    case SYSTEM_EVENT_TYPES.USER_UNBANNED:
      return `${subjectName} has been unbanned by ${actorName}`;
    case SYSTEM_EVENT_TYPES.USER_PROMOTED:
      return `${actorName} promoted ${subjectName}`;
    case SYSTEM_EVENT_TYPES.USER_DEMOTED:
      return `${actorName} demoted ${subjectName}`;
    case SYSTEM_EVENT_TYPES.LEADERSHIP_TRANSFERRED:
      return `${actorName} transferred leadership to ${subjectName}`;
    case SYSTEM_EVENT_TYPES.GROUP_NAME_UPDATED:
      return `${actorName} updated the group name`;
    case SYSTEM_EVENT_TYPES.GROUP_DESCRIPTION_UPDATED:
      return `${actorName} updated the group description`;
    case SYSTEM_EVENT_TYPES.GROUP_ARCHIVED:
      return `${actorName} archived the group`;
    default:
      return message.content || "System event";
  }
}

function formatMessage(message: DisplayChatMessage, username: string | null): FormattedMessage {
  if (message.messageType === MESSAGE_TYPES.SYSTEM && message.systemEventType) {
    return {
      type: "system",
      content: formatStructuredSystemMessage(message),
      isDisconnected: false,
      isConnected: false,
    };
  }

  const isLegacySystemMessage = Boolean(message.content && message.content.startsWith("[SYSTEM] "));

  if (isLegacySystemMessage && message.content) {
    const systemContent = message.content.replace("[SYSTEM] ", "").toLowerCase();
    const isDisconnected = systemContent.includes("disconnected");
    const isConnected = systemContent.includes("connected");

    return {
      type: "system",
      content: message.content.replace("[SYSTEM] ", ""),
      isDisconnected,
      isConnected,
    };
  }

  const displayName =
    message.user && message.user.fullname ? message.user.fullname : message.user ? message.user.username : "Unknown";
  const messageUsername = message.user ? message.user.username : null;
  const isOwnMessage = messageUsername === username;
  const editorName = message.updatedBy
    ? (message.updatedBy.fullname || message.updatedBy.username)
    : null;
  const editedByOther = Boolean(
    message.updatedAt
    && !message.deletedAt
    && message.updatedBy?.username
    && message.updatedBy.username !== messageUsername,
  );

  return {
    type: isOwnMessage ? "sent" : "received",
    displayName,
    content: message.deletedAt ? "Message deleted" : message.content,
    timestamp: message.timestamp,
    relativeTimestamp: formatRelativeTime(message.timestamp),
    absoluteTimestamp: formatAbsoluteTimeVi(message.timestamp),
    isEdited: Boolean(message.updatedAt) && !message.deletedAt,
    editedByLabel: editedByOther && editorName
      ? ` (edited by ${editorName})`
      : (message.updatedAt && !message.deletedAt ? " (edited)" : ""),
    messageType: message.messageType || MESSAGE_TYPES.TEXT,
    attachments: Array.isArray(message.attachments) ? message.attachments : [],
    localUploadState: message.localUploadState || null,
    processingIndicator: getProcessingIndicator(message),
  };
}

function ChatMessageItem({
  message,
  username,
  currentUserPermissions,
  onRetryPendingMessage,
  onCancelPendingMessage,
  onDismissPendingMessage,
  onMessageModerated,
}: ChatMessageItemProps) {
  const [menuAnchorEl, setMenuAnchorEl] = useState<HTMLElement | null>(null);
  const [isEditing, setIsEditing] = useState(false);
  const [editDraft, setEditDraft] = useState("");
  const [editError, setEditError] = useState("");
  const [isSavingEdit, setIsSavingEdit] = useState(false);
  const [deleteConfirmOpen, setDeleteConfirmOpen] = useState(false);
  const [deleteError, setDeleteError] = useState("");
  const [isDeleting, setIsDeleting] = useState(false);

  const formatted = formatMessage(message, username);
  const permissions = currentUserPermissions || [];
  const showEdit = canEditMessage({ message, username, permissions });
  const showDelete = canDeleteMessage({ message, username, permissions });
  const showActions = (showEdit || showDelete) && !isEditing;

  if (formatted.type === "system") {
    const systemClass = formatted.isDisconnected
      ? "disconnected"
      : formatted.isConnected
        ? "connected"
        : "default";
    return (
      <div className="chat-message-item-wrapper">
        <Box className={`chat-message-system-message ${systemClass}`}>
          <Typography variant="caption" className={`chat-message-system-text ${systemClass}`}>
            {formatted.content}
          </Typography>
        </Box>
      </div>
    );
  }

  const isOwnMessage = formatted.type === "sent";

  const openMenu = (event: React.MouseEvent<HTMLElement>) => {
    setMenuAnchorEl(event.currentTarget);
  };

  const closeMenu = () => {
    setMenuAnchorEl(null);
  };

  const startEditing = () => {
    closeMenu();
    setEditDraft(message.content || "");
    setEditError("");
    setIsEditing(true);
  };

  const cancelEditing = () => {
    if (isSavingEdit) {
      return;
    }
    setIsEditing(false);
    setEditDraft("");
    setEditError("");
  };

  const saveEdit = async () => {
    if (message.id == null) {
      setEditError("Message id is missing");
      return;
    }

    const nextContent = editDraft.trim();
    if (!nextContent) {
      setEditError("Message content is required");
      return;
    }
    if (nextContent === (message.content || "").trim()) {
      setEditError("Message content is unchanged");
      return;
    }

    setIsSavingEdit(true);
    setEditError("");
    try {
      const updated = await updateMessage(message.id, nextContent);
      onMessageModerated?.(updated);
      setIsEditing(false);
      setEditDraft("");
    } catch (error: unknown) {
      console.error("Error editing message:", error);
      setEditError(toErrorMessage(error, "Failed to edit message"));
    } finally {
      setIsSavingEdit(false);
    }
  };

  const openDeleteConfirm = () => {
    closeMenu();
    setDeleteError("");
    setDeleteConfirmOpen(true);
  };

  const closeDeleteConfirm = () => {
    if (isDeleting) {
      return;
    }
    setDeleteConfirmOpen(false);
    setDeleteError("");
  };

  const confirmDelete = async () => {
    if (message.id == null) {
      setDeleteError("Message id is missing");
      return;
    }

    setIsDeleting(true);
    setDeleteError("");
    try {
      const updated = await deleteMessage(message.id);
      onMessageModerated?.(updated);
      setDeleteConfirmOpen(false);
    } catch (error: unknown) {
      console.error("Error deleting message:", error);
      setDeleteError(toErrorMessage(error, "Failed to delete message"));
    } finally {
      setIsDeleting(false);
    }
  };

  return (
    <div className="chat-message-item-wrapper">
      <Box className={`chat-message-bubble-wrapper ${isOwnMessage ? "own" : "other"}`}>
        <Paper className={`chat-message-bubble ${isOwnMessage ? "own" : "other"}`}>
          <Typography
            variant="caption"
            className={`chat-message-sender-info ${isOwnMessage ? "own" : ""}`}
          >
            <Box component="span" className="chat-message-sender-name">
              {formatted.displayName}
            </Box>
            <Box className="chat-message-meta-actions">
              <Box
                component="span"
                className="chat-message-timestamp"
                title={formatted.absoluteTimestamp}
              >
                {formatted.relativeTimestamp}
                {formatted.editedByLabel}
              </Box>
              {showActions ? (
                <IconButton
                  size="small"
                  className="chat-message-actions-button"
                  onClick={openMenu}
                  title="Message actions"
                  aria-label="Message actions"
                >
                  <MoreVertIcon fontSize="inherit" />
                </IconButton>
              ) : null}
            </Box>
          </Typography>

          {isEditing ? (
            <Box className="chat-message-edit-form">
              <TextField
                value={editDraft}
                onChange={(event) => setEditDraft(event.target.value)}
                multiline
                minRows={2}
                maxRows={6}
                fullWidth
                size="small"
                disabled={isSavingEdit}
                autoFocus
                title="Edit message"
                aria-label="Edit message"
              />
              {editError ? (
                <Alert severity="error" className="chat-message-moderation-alert">
                  {editError}
                </Alert>
              ) : null}
              <Box className="chat-message-edit-actions">
                <Button
                  size="small"
                  onClick={cancelEditing}
                  disabled={isSavingEdit}
                  title="Cancel edit"
                  aria-label="Cancel edit"
                >
                  Cancel
                </Button>
                <Button
                  size="small"
                  variant="contained"
                  onClick={() => {
                    void saveEdit();
                  }}
                  disabled={isSavingEdit}
                  title="Save edit"
                  aria-label="Save edit"
                  startIcon={isSavingEdit ? <CircularProgress size={14} color="inherit" /> : null}
                >
                  Save
                </Button>
              </Box>
            </Box>
          ) : (
            <>
              <Typography variant="body2" className="chat-message-text">
                {formatted.content}
              </Typography>
              {isMediaMessageType(formatted.messageType) && !message.deletedAt ? (
                <MessageMediaContent
                  message={message}
                  formatted={formatted}
                  onRetryPendingMessage={onRetryPendingMessage}
                  onCancelPendingMessage={onCancelPendingMessage}
                  onDismissPendingMessage={onDismissPendingMessage}
                />
              ) : null}
            </>
          )}
        </Paper>
      </Box>

      <Menu
        anchorEl={menuAnchorEl}
        open={Boolean(menuAnchorEl)}
        onClose={closeMenu}
        anchorOrigin={{ vertical: "bottom", horizontal: isOwnMessage ? "right" : "left" }}
        transformOrigin={{ vertical: "top", horizontal: isOwnMessage ? "right" : "left" }}
      >
        {showEdit ? (
          <MenuItem
            onClick={startEditing}
            title="Edit message"
            aria-label="Edit message"
          >
            Edit
          </MenuItem>
        ) : null}
        {showDelete ? (
          <MenuItem
            onClick={openDeleteConfirm}
            title="Delete message"
            aria-label="Delete message"
          >
            Delete
          </MenuItem>
        ) : null}
      </Menu>

      <Dialog open={deleteConfirmOpen} onClose={closeDeleteConfirm} maxWidth="xs" fullWidth>
        <DialogTitle>Delete message?</DialogTitle>
        <DialogContent>
          {deleteError ? (
            <Alert severity="error" sx={{ mb: 1.5 }}>
              {deleteError}
            </Alert>
          ) : null}
          <DialogContentText>
            This removes the message content for everyone. The placeholder stays in the chat history.
          </DialogContentText>
        </DialogContent>
        <DialogActions>
          <Button
            onClick={closeDeleteConfirm}
            disabled={isDeleting}
            title="Cancel delete"
            aria-label="Cancel delete"
          >
            Cancel
          </Button>
          <Button
            color="error"
            variant="contained"
            onClick={() => {
              void confirmDelete();
            }}
            disabled={isDeleting}
            title="Confirm delete"
            aria-label="Confirm delete"
            startIcon={isDeleting ? <CircularProgress size={14} color="inherit" /> : null}
          >
            Delete
          </Button>
        </DialogActions>
      </Dialog>
    </div>
  );
}

export default ChatMessageItem;

function toErrorMessage(error: unknown, fallbackMessage: string): string {
  return error instanceof Error ? error.message : fallbackMessage;
}
