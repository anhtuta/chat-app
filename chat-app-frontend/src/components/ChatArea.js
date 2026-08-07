import React, { useState, useEffect, useRef, useMemo, useCallback } from "react";
import { Box } from "@mui/material";
import ChatAreaHeader from "./chat-area/ChatAreaHeader";
import ChatMessageList from "./chat-area/ChatMessageList";
import ChatMessageComposer from "./chat-area/ChatMessageComposer";
import {
  completeMediaMessage,
  prepareMediaMessage,
  requestMultipartPartUrls,
  uploadBlobToPresignedUrl,
  uploadFileToPresignedUrl,
} from "../services/api";
import {
  isPreviewableFile,
  LOCAL_UPLOAD_STATUSES,
  resolveMessageTypeFromFiles,
  validateSelectedFiles,
} from "./chat-area/mediaUtils";
import "./ChatArea.css";

const MULTIPART_PART_URL_BATCH_SIZE = 4;

function ChatArea({
  chatId,
  chatName,
  currentGroup,
  messages,
  isLoading,
  isLoadingOlder,
  hasMoreMessages,
  isConnected,
  username,
  onSendMessage,
  onMediaMessageDelivered,
  onMessageModerated,
  onLoadOlderMessages,
  onOpenGroupDetails,
  onLogout,
}) {
  const AUTO_FILL_MAX_BATCHES = 3;
  const [selectedMedia, setSelectedMedia] = useState([]);
  const [mediaComposerError, setMediaComposerError] = useState("");
  const [pendingMediaMessages, setPendingMediaMessages] = useState([]);
  const [showLoadOlderFallback, setShowLoadOlderFallback] = useState(false);
  const chatMessagesRef = useRef(null);
  const messagesEndRef = useRef(null);
  const isPrependingRef = useRef(false);
  const isLoadingOlderRef = useRef(false);
  const prevMessagesRef = useRef([]);
  const forceScrollToBottomRef = useRef(true);
  const pendingMediaMessagesRef = useRef([]);
  const selectedMediaRef = useRef([]);
  const uploadTasksRef = useRef(new Map());
  const autoFillBatchCountRef = useRef(0);
  const isAutoFillingRef = useRef(false);

  useEffect(() => {
    pendingMediaMessagesRef.current = pendingMediaMessages;
  }, [pendingMediaMessages]);

  useEffect(() => {
    selectedMediaRef.current = selectedMedia;
  }, [selectedMedia]);

  useEffect(() => {
    isLoadingOlderRef.current = isLoadingOlder;
  }, [isLoadingOlder]);

  useEffect(() => (
    () => {
      uploadTasksRef.current.forEach((task) => {
        task.canceled = true;
        task.abort?.();
      });
      revokePreviewUrls(pendingMediaMessagesRef.current);
      revokePreviewUrls(selectedMediaRef.current);
    }
  ), []);

  const visiblePendingMediaMessages = useMemo(
    () => pendingMediaMessages.filter((message) => message.chatKey === buildChatKey(chatId)),
    [chatId, pendingMediaMessages],
  );

  const displayMessages = useMemo(
    () => [...messages, ...visiblePendingMediaMessages],
    [messages, visiblePendingMediaMessages],
  );

  const scrollToBottom = useCallback((behavior = "smooth") => {
    messagesEndRef.current?.scrollIntoView({ behavior });
  }, []);

  useEffect(() => {
    const container = chatMessagesRef.current;
    const prevMessages = prevMessagesRef.current;

    const getMessageKey = (message) => {
      if (!message) return "";
      return `${message.id ?? "no-id"}-${message.timestamp ?? "no-time"}-${message.content ?? ""}`;
    };

    const prevFirstKey = getMessageKey(prevMessages[0]);
    const prevLastKey = getMessageKey(prevMessages[prevMessages.length - 1]);
    const nextFirstKey = getMessageKey(displayMessages[0]);
    const nextLastKey = getMessageKey(displayMessages[displayMessages.length - 1]);

    const grew = displayMessages.length > prevMessages.length;
    const didPrepend = grew && prevLastKey === nextLastKey && prevFirstKey !== nextFirstKey;
    const didAppend = grew && prevLastKey !== nextLastKey;

    const distanceFromBottom = container
      ? container.scrollHeight - container.scrollTop - container.clientHeight
      : Number.MAX_SAFE_INTEGER;
    const wasNearBottom = distanceFromBottom < 120;

    if (!isPrependingRef.current && !didPrepend) {
      if (forceScrollToBottomRef.current || (didAppend && wasNearBottom)) {
        scrollToBottom();
      }
    }

    forceScrollToBottomRef.current = false;
    prevMessagesRef.current = displayMessages;
  }, [displayMessages, scrollToBottom]);

  useEffect(() => {
    isPrependingRef.current = false;
    forceScrollToBottomRef.current = true;
    prevMessagesRef.current = [];
    autoFillBatchCountRef.current = 0;
    isAutoFillingRef.current = false;
    setShowLoadOlderFallback(false);
  }, [chatId]);

  const isContainerScrollable = useCallback((container) => {
    if (!container) {
      return false;
    }

    return container.scrollHeight > container.clientHeight + 1;
  }, []);

  const loadOlderMessages = useCallback(async ({ preserveViewport }) => {
    const container = chatMessagesRef.current;
    if (
      !container ||
      !hasMoreMessages ||
      isLoadingOlderRef.current ||
      isLoading ||
      !onLoadOlderMessages
    ) {
      return false;
    }

    const previousHeight = container.scrollHeight;
    const previousTop = container.scrollTop;

    isPrependingRef.current = true;
    const loaded = await onLoadOlderMessages();

    if (!loaded) {
      isPrependingRef.current = false;
      return false;
    }

    requestAnimationFrame(() => {
      const updatedContainer = chatMessagesRef.current;
      if (!updatedContainer) {
        isPrependingRef.current = false;
        return;
      }

      if (preserveViewport) {
        updatedContainer.scrollTop = updatedContainer.scrollHeight - previousHeight + previousTop;
      } else {
        updatedContainer.scrollTop = updatedContainer.scrollHeight;
      }

      isPrependingRef.current = false;
    });

    return true;
  }, [hasMoreMessages, isLoading, onLoadOlderMessages]);

  const handleMessagesScroll = async (event) => {
    const container = event.currentTarget;
    if (
      container.scrollTop > 40 ||
      !hasMoreMessages ||
      isLoadingOlderRef.current ||
      isLoading ||
      !onLoadOlderMessages
    ) {
      return;
    }

    await loadOlderMessages({ preserveViewport: true });
  };

  useEffect(() => {
    if (
      chatId === "public" ||
      isLoading ||
      isLoadingOlder ||
      !hasMoreMessages ||
      !onLoadOlderMessages ||
      !displayMessages.length
    ) {
      if (chatId === "public" || !hasMoreMessages) {
        setShowLoadOlderFallback(false);
      }
      return;
    }

    const container = chatMessagesRef.current;
    if (!container) {
      return;
    }

    if (isContainerScrollable(container)) {
      setShowLoadOlderFallback(false);
      return;
    }

    if (autoFillBatchCountRef.current >= AUTO_FILL_MAX_BATCHES) {
      setShowLoadOlderFallback(true);
      return;
    }

    if (isAutoFillingRef.current) {
      return;
    }

    let canceled = false;
    isAutoFillingRef.current = true;

    requestAnimationFrame(async () => {
      if (canceled) {
        isAutoFillingRef.current = false;
        return;
      }

      const currentContainer = chatMessagesRef.current;
      if (!currentContainer || isContainerScrollable(currentContainer) || !hasMoreMessages) {
        isAutoFillingRef.current = false;
        return;
      }

      // Auto-top-off the first load so tall viewports still expose older history.
      autoFillBatchCountRef.current += 1;
      setShowLoadOlderFallback(false);
      await loadOlderMessages({ preserveViewport: false });
      isAutoFillingRef.current = false;
    });

    return () => {
      canceled = true;
    };
  }, [
    AUTO_FILL_MAX_BATCHES,
    chatId,
    displayMessages,
    hasMoreMessages,
    isContainerScrollable,
    isLoading,
    isLoadingOlder,
    loadOlderMessages,
    onLoadOlderMessages,
  ]);

  const handleLoadOlderFallbackClick = async () => {
    setShowLoadOlderFallback(false);
    await loadOlderMessages({ preserveViewport: false });
  };

  const handleFilesSelected = (files) => {
    const nextFiles = Array.from(files || []);
    if (!nextFiles.length) {
      return;
    }

    const validationMessage = validateSelectedFiles(nextFiles);
    if (validationMessage) {
      setMediaComposerError(validationMessage);
      return;
    }

    setMediaComposerError("");
    setSelectedMedia((previousSelection) => {
      revokePreviewUrls(previousSelection);
      return nextFiles.map((file, index) => ({
        localId: `composer-${Date.now()}-${index}`,
        file,
        originalFilename: file.name,
        mimeType: file.type || "application/octet-stream",
        sizeBytes: file.size,
        localPreviewUrl: isPreviewableFile(file) ? URL.createObjectURL(file) : null,
      }));
    });
  };

  const handleRemoveSelectedMedia = (localId) => {
    setSelectedMedia((previousSelection) => {
      const nextSelection = previousSelection.filter((attachment) => attachment.localId !== localId);
      const removedAttachment = previousSelection.find((attachment) => attachment.localId === localId);
      revokePreviewUrls(removedAttachment ? [removedAttachment] : []);
      return nextSelection;
    });
  };

  const clearSelectedMedia = ({ revoke = true } = {}) => {
    setSelectedMedia((previousSelection) => {
      if (revoke) {
        revokePreviewUrls(previousSelection);
      }
      return [];
    });
  };

  const updatePendingMessage = (localId, updater) => {
    setPendingMediaMessages((previousMessages) => previousMessages.map((message) => (
      message.localId === localId ? updater(message) : message
    )));
  };

  const removePendingMessage = (localId) => {
    setPendingMediaMessages((previousMessages) => {
      const nextMessages = previousMessages.filter((message) => message.localId !== localId);
      const removedMessage = previousMessages.find((message) => message.localId === localId);
      if (removedMessage) {
        revokePreviewUrls(removedMessage.attachments);
      }
      return nextMessages;
    });
  };

  const buildPendingMessage = (attachments, messageType) => ({
    localId: `pending-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
    chatKey: buildChatKey(chatId),
    groupId: chatId === "public" ? null : Number(chatId),
    messageType,
    content: null,
    timestamp: new Date().toISOString(),
    user: {
      username,
      fullname: username,
    },
    attachments: attachments.map((attachment, index) => ({
      id: attachment.localId,
      attachmentOrder: index,
      originalFilename: attachment.originalFilename,
      mimeType: attachment.mimeType,
      sizeBytes: attachment.sizeBytes,
      localPreviewUrl: attachment.localPreviewUrl,
      file: attachment.file,
      status: LOCAL_UPLOAD_STATUSES.UPLOAD_IN_PROGRESS,
    })),
    localUploadState: {
      status: LOCAL_UPLOAD_STATUSES.UPLOAD_IN_PROGRESS,
      progressPercent: 0,
      errorMessage: "",
    },
  });

  const updateUploadProgress = (localId, uploadedBytes, totalBytes) => {
    const progressPercent = totalBytes > 0 ? Math.round((uploadedBytes / totalBytes) * 100) : 0;
    updatePendingMessage(localId, (currentMessage) => ({
      ...currentMessage,
      localUploadState: {
        ...currentMessage.localUploadState,
        status: LOCAL_UPLOAD_STATUSES.UPLOAD_IN_PROGRESS,
        progressPercent,
        errorMessage: "",
      },
    }));
  };

  const uploadMultipartFile = async ({
    uploadSessionId,
    preparedAttachment,
    file,
    task,
    baseUploadedBytes,
    totalBytes,
    pendingMessageLocalId,
  }) => {
    const partSize = Number(preparedAttachment.recommendedPartSize);
    if (!Number.isFinite(partSize) || partSize <= 0) {
      throw new Error("The upload session did not include a valid multipart part size.");
    }

    const partNumbers = Array.from(
      { length: Math.ceil(file.size / partSize) },
      (_, index) => index + 1,
    );
    const loadedBytesByPart = new Map();
    const activeUploadHandles = new Map();
    const completedParts = [];

    const updateMultipartProgress = () => {
      const currentFileUploadedBytes = Array.from(loadedBytesByPart.values())
        .reduce((sum, loadedBytes) => sum + loadedBytes, 0);
      updateUploadProgress(
        pendingMessageLocalId,
        baseUploadedBytes + Math.min(currentFileUploadedBytes, file.size),
        totalBytes,
      );
    };

    task.abort = () => {
      activeUploadHandles.forEach((uploadHandle) => uploadHandle.abort());
    };

    for (let batchStart = 0; batchStart < partNumbers.length; batchStart += MULTIPART_PART_URL_BATCH_SIZE) {
      if (task.canceled) {
        throw createAbortError();
      }

      const batchPartNumbers = partNumbers.slice(batchStart, batchStart + MULTIPART_PART_URL_BATCH_SIZE);
      const partUrlResponse = await requestMultipartPartUrls(
        uploadSessionId,
        preparedAttachment.attachmentId,
        batchPartNumbers,
      );
      const presignedUrlByPartNumber = new Map(
        (partUrlResponse.parts || []).map((part) => [part.partNumber, part.presignedUrl]),
      );

      const uploadedBatchParts = await Promise.all(batchPartNumbers.map(async (partNumber) => {
        const presignedUrl = presignedUrlByPartNumber.get(partNumber);
        if (!presignedUrl) {
          throw new Error(`Missing multipart upload URL for part ${partNumber}.`);
        }

        const startByte = (partNumber - 1) * partSize;
        const endByte = Math.min(startByte + partSize, file.size);
        const partBlob = file.slice(startByte, endByte, file.type || undefined);
        const uploadHandle = uploadBlobToPresignedUrl(presignedUrl, partBlob, {
          onProgress: (loadedBytes) => {
            loadedBytesByPart.set(partNumber, Math.min(loadedBytes, partBlob.size));
            updateMultipartProgress();
          },
        });

        activeUploadHandles.set(partNumber, uploadHandle);
        try {
          const etag = await uploadHandle.promise;
          loadedBytesByPart.set(partNumber, partBlob.size);
          updateMultipartProgress();
          return { partNumber, etag };
        } finally {
          activeUploadHandles.delete(partNumber);
        }
      }));

      completedParts.push(...uploadedBatchParts);
    }

    task.abort = null;
    return completedParts.sort((left, right) => left.partNumber - right.partNumber);
  };

  const uploadPendingMessage = async (pendingMessage) => {
    const files = pendingMessage.attachments.map((attachment) => attachment.file).filter(Boolean);
    const task = {
      canceled: false,
      abort: null,
    };
    uploadTasksRef.current.set(pendingMessage.localId, task);

    try {
      const prepareResponse = await prepareMediaMessage({
        chatScope: pendingMessage.chatKey === "public" ? "PUBLIC" : "GROUP",
        groupId: pendingMessage.groupId,
        messageType: pendingMessage.messageType,
        attachments: files.map((file) => ({
          filename: file.name,
          mimeType: file.type || "application/octet-stream",
          sizeBytes: file.size,
        })),
      });

      if (task.canceled) {
        throw createAbortError();
      }

      if ((prepareResponse.attachments || []).length !== files.length) {
        throw new Error("The upload session response did not match the selected attachments.");
      }

      const totalBytes = files.reduce((sum, file) => sum + file.size, 0);
      let uploadedBytesBeforeCurrentFile = 0;
      const completionAttachments = [];

      for (let index = 0; index < files.length; index += 1) {
        const preparedAttachment = prepareResponse.attachments[index];
        const file = files[index];
        const baseUploadedBytes = uploadedBytesBeforeCurrentFile;

        if (preparedAttachment.uploadStrategy === "MULTIPART") {
          const parts = await uploadMultipartFile({
            uploadSessionId: prepareResponse.uploadSessionId,
            preparedAttachment,
            file,
            task,
            baseUploadedBytes,
            totalBytes,
            pendingMessageLocalId: pendingMessage.localId,
          });
          uploadedBytesBeforeCurrentFile += file.size;
          completionAttachments.push({
            attachmentId: preparedAttachment.attachmentId,
            parts,
          });
          continue;
        }

        if (preparedAttachment.uploadStrategy !== "SINGLE_PART") {
          throw new Error(`Unsupported upload strategy: ${preparedAttachment.uploadStrategy}`);
        }

        if (!preparedAttachment.presignedUrl) {
          throw new Error("The upload session did not include a presigned upload URL.");
        }

        const uploadHandle = uploadFileToPresignedUrl(preparedAttachment.presignedUrl, file, {
          onProgress: (loadedBytes) => {
            const totalUploadedBytes = baseUploadedBytes + Math.min(loadedBytes, file.size);
            updateUploadProgress(pendingMessage.localId, totalUploadedBytes, totalBytes);
          },
        });

        task.abort = uploadHandle.abort;
        const etag = await uploadHandle.promise;
        uploadedBytesBeforeCurrentFile += file.size;
        completionAttachments.push({
          attachmentId: preparedAttachment.attachmentId,
          etag,
        });
      }

      task.abort = null;
      updatePendingMessage(pendingMessage.localId, (currentMessage) => ({
        ...currentMessage,
        localUploadState: {
          ...currentMessage.localUploadState,
          status: LOCAL_UPLOAD_STATUSES.FINALIZING,
          progressPercent: 100,
          errorMessage: "",
        },
      }));

      const completedMessage = await completeMediaMessage(prepareResponse.uploadSessionId, completionAttachments);
      uploadTasksRef.current.delete(pendingMessage.localId);
      removePendingMessage(pendingMessage.localId);
      onMediaMessageDelivered?.(completedMessage);
    } catch (error) {
      uploadTasksRef.current.delete(pendingMessage.localId);
      const canceled = task.canceled || error?.name === "AbortError";
      updatePendingMessage(pendingMessage.localId, (currentMessage) => ({
        ...currentMessage,
        localUploadState: {
          ...currentMessage.localUploadState,
          status: canceled ? LOCAL_UPLOAD_STATUSES.CANCELED : LOCAL_UPLOAD_STATUSES.UPLOAD_FAILED,
          progressPercent: currentMessage.localUploadState?.progressPercent || 0,
          errorMessage: error?.message || "Upload failed",
        },
      }));
    }
  };

  const handleSendText = (content) => {
    onSendMessage(content);
    setMediaComposerError("");
  };

  const handleSendMedia = () => {
    const messageType = resolveMessageTypeFromFiles(selectedMedia.map((attachment) => attachment.file));
    if (!messageType) {
      setMediaComposerError(
        "Only image batches are supported. Video, audio, and file messages must contain exactly one attachment.",
      );
      return;
    }

    const pendingMessage = buildPendingMessage(selectedMedia, messageType);
    setPendingMediaMessages((previousMessages) => [...previousMessages, pendingMessage]);
    setMediaComposerError("");
    clearSelectedMedia({ revoke: false });
    uploadPendingMessage(pendingMessage);
  };

  const handleRetryPendingMedia = (localId) => {
    const pendingMessage = pendingMediaMessagesRef.current.find((message) => message.localId === localId);
    if (!pendingMessage || uploadTasksRef.current.has(localId)) {
      return;
    }

    updatePendingMessage(localId, (currentMessage) => ({
      ...currentMessage,
      timestamp: new Date().toISOString(),
      localUploadState: {
        ...currentMessage.localUploadState,
        status: LOCAL_UPLOAD_STATUSES.UPLOAD_IN_PROGRESS,
        progressPercent: 0,
        errorMessage: "",
      },
    }));
    uploadPendingMessage(pendingMessage);
  };

  const handleCancelPendingMedia = (localId) => {
    const task = uploadTasksRef.current.get(localId);
    if (!task) {
      updatePendingMessage(localId, (currentMessage) => ({
        ...currentMessage,
        localUploadState: {
          ...currentMessage.localUploadState,
          status: LOCAL_UPLOAD_STATUSES.CANCELED,
          errorMessage: "Upload canceled",
        },
      }));
      return;
    }

    task.canceled = true;
    task.abort?.();
    updatePendingMessage(localId, (currentMessage) => ({
      ...currentMessage,
      localUploadState: {
        ...currentMessage.localUploadState,
        status: LOCAL_UPLOAD_STATUSES.CANCELED,
        errorMessage: "Upload canceled",
      },
    }));
  };

  return (
    <div className="chat-area-wrapper">
      <Box className="chat-area-flex-container">
        <ChatAreaHeader
          chatName={chatName}
          isConnected={isConnected}
          onLogout={onLogout}
          onOpenGroupDetails={onOpenGroupDetails}
          showGroupDetailsAction={chatId !== "public"}
        />
        <ChatMessageList
          chatMessagesRef={chatMessagesRef}
          messagesEndRef={messagesEndRef}
          messages={displayMessages}
          username={username}
          currentUserPermissions={currentGroup?.currentUserPermissions}
          isLoading={isLoading}
          isLoadingOlder={isLoadingOlder}
          onScroll={handleMessagesScroll}
          showLoadOlderFallback={showLoadOlderFallback}
          onLoadOlderFallback={handleLoadOlderFallbackClick}
          onRetryPendingMessage={handleRetryPendingMedia}
          onCancelPendingMessage={handleCancelPendingMedia}
          onDismissPendingMessage={removePendingMessage}
          onMessageModerated={onMessageModerated}
        />
        <ChatMessageComposer
          onSendMessage={handleSendText}
          onSendMedia={handleSendMedia}
          selectedMedia={selectedMedia}
          onSelectFiles={handleFilesSelected}
          onRemoveSelectedMedia={handleRemoveSelectedMedia}
          onClearSelectedMedia={() => clearSelectedMedia()}
          mediaError={mediaComposerError}
        />
      </Box>
    </div>
  );
}

export default ChatArea;

function buildChatKey(chatId) {
  return chatId === "public" ? "public" : String(chatId);
}

function revokePreviewUrls(attachments) {
  (attachments || []).forEach((attachment) => {
    if (attachment?.localPreviewUrl) {
      URL.revokeObjectURL(attachment.localPreviewUrl);
    }
  });
}

function createAbortError() {
  const abortError = new Error("Upload canceled");
  abortError.name = "AbortError";
  return abortError;
}
