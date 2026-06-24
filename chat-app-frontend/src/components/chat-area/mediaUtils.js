export const MESSAGE_TYPES = {
  TEXT: "TEXT",
  IMAGE: "IMAGE",
  VIDEO: "VIDEO",
  AUDIO: "AUDIO",
  FILE: "FILE",
  SYSTEM: "SYSTEM",
};

export const LOCAL_UPLOAD_STATUSES = {
  UPLOAD_IN_PROGRESS: "UPLOAD_IN_PROGRESS",
  FINALIZING: "FINALIZING",
  UPLOAD_FAILED: "UPLOAD_FAILED",
  CANCELED: "CANCELED",
};

export function isMediaMessageType(messageType) {
  return (
    messageType === MESSAGE_TYPES.IMAGE ||
    messageType === MESSAGE_TYPES.VIDEO ||
    messageType === MESSAGE_TYPES.AUDIO ||
    messageType === MESSAGE_TYPES.FILE
  );
}

export function resolveMessageTypeFromFiles(files) {
  if (!files.length) {
    return null;
  }

  const everyImage = files.every((file) => (file.type || "").startsWith("image/"));
  if (files.length > 1) {
    return everyImage ? MESSAGE_TYPES.IMAGE : null;
  }

  const [file] = files;
  const mimeType = file.type || "";
  if (mimeType.startsWith("image/")) {
    return MESSAGE_TYPES.IMAGE;
  }
  if (mimeType.startsWith("video/")) {
    return MESSAGE_TYPES.VIDEO;
  }
  if (mimeType.startsWith("audio/")) {
    return MESSAGE_TYPES.AUDIO;
  }
  return MESSAGE_TYPES.FILE;
}

export function validateSelectedFiles(files) {
  if (!files.length) {
    return "Choose at least one file.";
  }

  const messageType = resolveMessageTypeFromFiles(files);
  if (!messageType) {
    return "Only image batches are supported. Video, audio, and file messages must contain exactly one attachment.";
  }

  return null;
}

export function isPreviewableFile(file) {
  const mimeType = file?.type || "";
  return (
    mimeType.startsWith("image/") ||
    mimeType.startsWith("video/") ||
    mimeType.startsWith("audio/")
  );
}

export function formatBytes(bytes) {
  if (bytes === undefined || bytes === null || Number.isNaN(Number(bytes))) {
    return "";
  }

  const value = Number(bytes);
  if (value < 1024) {
    return `${value} B`;
  }

  const units = ["KB", "MB", "GB", "TB"];
  let unitIndex = -1;
  let size = value;

  while (size >= 1024 && unitIndex < units.length - 1) {
    size /= 1024;
    unitIndex += 1;
  }

  return `${size.toFixed(size >= 10 ? 0 : 1)} ${units[unitIndex]}`;
}

export function getAttachmentDisplayUrl(messageType, attachment) {
  if (!attachment) {
    return null;
  }

  if (attachment.localPreviewUrl) {
    return attachment.localPreviewUrl;
  }

  if (messageType === MESSAGE_TYPES.IMAGE) {
    return attachment.previewUrl || attachment.thumbnailUrl || attachment.contentUrl || null;
  }

  if (messageType === MESSAGE_TYPES.VIDEO) {
    return attachment.transcodedUrl || attachment.contentUrl || null;
  }

  if (messageType === MESSAGE_TYPES.AUDIO) {
    return attachment.transcodedUrl || attachment.contentUrl || null;
  }

  return attachment.contentUrl || null;
}

export function getProcessingIndicator(message) {
  if (!message || !Array.isArray(message.attachments)) {
    return null;
  }

  if (message.messageType !== MESSAGE_TYPES.IMAGE && message.messageType !== MESSAGE_TYPES.VIDEO) {
    return null;
  }

  const statuses = message.attachments
    .map((attachment) => attachment?.status)
    .filter(Boolean);

  if (!statuses.length) {
    return null;
  }

  if (statuses.some((status) => status === "PROCESSING_FAILED")) {
    return {
      tone: "error",
      label: "Processing failed",
      description: "The original upload is still available, but optimized previews could not be prepared.",
    };
  }

  if (
    statuses.some(
      (status) => status === "PROCESSING_PENDING" || status === "PROCESSING_IN_PROGRESS",
    )
  ) {
    return {
      tone: "info",
      label: "Processing media",
      description: "Preview quality may improve after background processing finishes.",
    };
  }

  return null;
}

export function getLocalUploadStatusCopy(status) {
  switch (status) {
    case LOCAL_UPLOAD_STATUSES.FINALIZING:
      return {
        title: "Publishing message",
        description: "Upload finished. Waiting for the backend to create the final chat message.",
      };
    case LOCAL_UPLOAD_STATUSES.UPLOAD_FAILED:
      return {
        title: "Upload failed",
        description: "You can retry this upload or dismiss the placeholder.",
      };
    case LOCAL_UPLOAD_STATUSES.CANCELED:
      return {
        title: "Upload canceled",
        description: "The media message was not published.",
      };
    case LOCAL_UPLOAD_STATUSES.UPLOAD_IN_PROGRESS:
    default:
      return {
        title: "Uploading media",
        description: "Your file is being uploaded directly to object storage.",
      };
  }
}
