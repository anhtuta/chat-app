import { MESSAGE_TYPES, isMediaMessageType } from "../components/chat-area/mediaUtils";
import type { ChatMessage, MessageType } from "../types/chat";

export const MESSAGE_MODERATION_PERMISSIONS = {
  EDIT_ANY_TEXT_MESSAGE: "EDIT_ANY_TEXT_MESSAGE",
  DELETE_ANY_MESSAGE: "DELETE_ANY_MESSAGE",
} as const;

const LATEST_MESSAGE_MAX_LENGTH = 255;

export function isMessageOwner(
  message: ChatMessage | null | undefined,
  username: string | null | undefined,
): boolean {
  if (!message?.user?.username || !username) {
    return false;
  }
  return message.user.username === username;
}

export function canEditMessage({
  message,
  username,
  permissions = [],
}: {
  message: ChatMessage | null | undefined;
  username: string | null | undefined;
  permissions?: string[] | null;
}): boolean {
  if (!message || message.deletedAt || message.localUploadState || message.id == null) {
    return false;
  }
  if (message.messageType !== MESSAGE_TYPES.TEXT) {
    return false;
  }
  if (isMessageOwner(message, username)) {
    return true;
  }
  return (permissions || []).includes(MESSAGE_MODERATION_PERMISSIONS.EDIT_ANY_TEXT_MESSAGE);
}

export function canDeleteMessage({
  message,
  username,
  permissions = [],
}: {
  message: ChatMessage | null | undefined;
  username: string | null | undefined;
  permissions?: string[] | null;
}): boolean {
  if (!message || message.deletedAt || message.localUploadState || message.id == null) {
    return false;
  }
  if (message.messageType === MESSAGE_TYPES.SYSTEM) {
    return false;
  }
  if (isMessageOwner(message, username)) {
    return true;
  }
  return (permissions || []).includes(MESSAGE_MODERATION_PERMISSIONS.DELETE_ANY_MESSAGE);
}

export function buildLatestMessagePreviewFromMessage(message: ChatMessage | null | undefined): string | null {
  if (!message) {
    return null;
  }
  if (message.deletedAt) {
    return "Message deleted";
  }

  const messageType = (message.messageType || MESSAGE_TYPES.TEXT) as MessageType;
  if (messageType === MESSAGE_TYPES.TEXT || messageType === MESSAGE_TYPES.SYSTEM) {
    return truncateLatestPreview(message.content);
  }
  if (messageType === MESSAGE_TYPES.IMAGE) {
    return (message.attachments?.length || 0) > 1 ? "Photos" : "Photo";
  }
  if (messageType === MESSAGE_TYPES.VIDEO) {
    return "Video";
  }
  if (messageType === MESSAGE_TYPES.AUDIO) {
    return "Audio";
  }
  if (messageType === MESSAGE_TYPES.FILE) {
    const filename = message.attachments?.[0]?.originalFilename;
    return filename ? truncateLatestPreview(filename) : "File";
  }
  if (isMediaMessageType(messageType)) {
    return "File";
  }
  return truncateLatestPreview(message.content);
}

export function truncateLatestPreview(content: string | null | undefined): string | null {
  if (content == null) {
    return null;
  }
  const normalized = content.trim();
  if (normalized.length <= LATEST_MESSAGE_MAX_LENGTH) {
    return normalized;
  }
  return normalized.substring(0, LATEST_MESSAGE_MAX_LENGTH);
}
