export type ChatScope = "PUBLIC" | "GROUP";

export type MessageType = "TEXT" | "IMAGE" | "VIDEO" | "AUDIO" | "FILE" | "SYSTEM";
export type SystemEventType =
  | "USER_JOINED"
  | "USER_LEFT"
  | "USER_KICKED"
  | "USER_BANNED"
  | "USER_UNBANNED"
  | "USER_PROMOTED"
  | "USER_DEMOTED"
  | "LEADERSHIP_TRANSFERRED"
  | "GROUP_NAME_UPDATED"
  | "GROUP_DESCRIPTION_UPDATED"
  | "GROUP_ARCHIVED";

export type MediaMessageType = Extract<MessageType, "IMAGE" | "VIDEO" | "AUDIO" | "FILE">;

export type LocalUploadStatus =
  | "UPLOAD_IN_PROGRESS"
  | "FINALIZING"
  | "UPLOAD_FAILED"
  | "CANCELED";

export type AttachmentProcessingStatus =
  | "PROCESSING_PENDING"
  | "PROCESSING_IN_PROGRESS"
  | "PROCESSING_FAILED"
  | "READY";

export interface ChatUser {
  username: string;
  fullname?: string | null;
}

export interface ChatAttachment {
  id?: number | string | null;
  attachmentId?: string;
  attachmentOrder?: number;
  originalFilename?: string | null;
  mimeType?: string | null;
  sizeBytes?: number | null;
  previewUrl?: string | null;
  thumbnailUrl?: string | null;
  transcodedUrl?: string | null;
  contentUrl?: string | null;
  localPreviewUrl?: string | null;
  status?: AttachmentProcessingStatus | LocalUploadStatus | string | null;
}

export interface PendingChatAttachment extends ChatAttachment {
  localId: string;
  file: File;
  originalFilename: string;
  mimeType: string;
  sizeBytes: number;
  localPreviewUrl: string | null;
}

export interface LocalUploadState {
  status: LocalUploadStatus;
  progressPercent: number;
  errorMessage: string;
}

export interface ChatMessage {
  id?: number | null;
  groupId?: number | null;
  content?: string | null;
  timestamp?: string | null;
  user?: ChatUser | null;
  messageType?: MessageType | null;
  systemEventType?: SystemEventType | null;
  systemEventActor?: ChatUser | null;
  updatedBy?: ChatUser | null;
  updatedAt?: string | null;
  deletedBy?: ChatUser | null;
  deletedAt?: string | null;
  attachments?: ChatAttachment[];
  localUploadState?: LocalUploadState | null;
}

export interface PendingMediaMessage extends ChatMessage {
  localId: string;
  chatKey: string;
  groupId: number | null;
  messageType: MediaMessageType;
  user: ChatUser;
  attachments: PendingChatAttachment[];
  localUploadState: LocalUploadState;
}

export interface GroupMessagesQuery {
  beforeTimestamp?: string | null;
  beforeId?: number | null;
  size?: number;
}

export interface PrepareMediaAttachmentInput {
  filename: string;
  mimeType: string;
  sizeBytes: number;
}

export interface PrepareMediaMessageRequest {
  chatScope: ChatScope;
  groupId?: number | null;
  messageType: MediaMessageType;
  attachments: PrepareMediaAttachmentInput[];
}

export interface PreparedMediaAttachment {
  attachmentId: string;
  presignedUrl: string;
  uploadStrategy: "SINGLE_PART" | string;
}

export interface PrepareMediaMessageResponse {
  uploadSessionId: string;
  attachments: PreparedMediaAttachment[];
}

export interface CompleteMediaAttachmentInput {
  attachmentId: string;
  etag: string;
}

export interface ProcessingIndicator {
  tone: "info" | "error";
  label: string;
  description: string;
}
