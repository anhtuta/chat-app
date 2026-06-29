import React, { useState, useEffect, useRef, useMemo } from "react";
import { Box } from "@mui/material";
import ChatAreaHeader from "./chat-area/ChatAreaHeader";
import ChatMessageList from "./chat-area/ChatMessageList";
import ChatMessageComposer from "./chat-area/ChatMessageComposer";
import { completeMediaMessage, prepareMediaMessage, uploadFileToPresignedUrl } from "../services/api";
import {
  isPreviewableFile,
  LOCAL_UPLOAD_STATUSES,
  resolveMessageTypeFromFiles,
  validateSelectedFiles,
} from "./chat-area/mediaUtils";
import "./ChatArea.css";

function ChatArea({
  chatId,
  chatName,
  messages,
  isLoading,
  isLoadingOlder,
  hasMoreMessages,
  isConnected,
  username,
  onSendMessage,
  onMediaMessageDelivered,
  onLoadOlderMessages,
  onLogout,
}) {
  const [messageInput, setMessageInput] = useState("");
  const [selectedMedia, setSelectedMedia] = useState([]);
  const [mediaComposerError, setMediaComposerError] = useState("");
  const [pendingMediaMessages, setPendingMediaMessages] = useState([]);
  const chatMessagesRef = useRef(null);
  const messagesEndRef = useRef(null);
  const isPrependingRef = useRef(false);
  const isLoadingOlderRef = useRef(false);
  const prevMessagesRef = useRef([]);
  const forceScrollToBottomRef = useRef(true);
  const pendingMediaMessagesRef = useRef([]);
  const selectedMediaRef = useRef([]);
  const uploadTasksRef = useRef(new Map());

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
  }, [displayMessages]);

  useEffect(() => {
    isPrependingRef.current = false;
    forceScrollToBottomRef.current = true;
    prevMessagesRef.current = [];
  }, [chatId]);

  const scrollToBottom = () => {
    messagesEndRef.current?.scrollIntoView({ behavior: "smooth" });
  };

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

    const previousHeight = container.scrollHeight;
    const previousTop = container.scrollTop;

    isPrependingRef.current = true;
    const loaded = await onLoadOlderMessages();

    if (!loaded) {
      isPrependingRef.current = false;
      return;
    }

    requestAnimationFrame(() => {
      const updatedHeight = container.scrollHeight;
      container.scrollTop = updatedHeight - previousHeight + previousTop;
      isPrependingRef.current = false;
    });
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

        if (preparedAttachment.uploadStrategy !== "SINGLE_PART") {
          // TODO: Replace this guard with browser multipart upload once backend multipart completion is real.
          throw new Error("This file is too large for the current UI upload flow. Try a smaller file for now.");
        }

        const uploadHandle = uploadFileToPresignedUrl(preparedAttachment.presignedUrl, file, {
          onProgress: (loadedBytes) => {
            const totalUploadedBytes = baseUploadedBytes + Math.min(loadedBytes, file.size);
            const progressPercent = totalBytes > 0 ? Math.round((totalUploadedBytes / totalBytes) * 100) : 0;
            updatePendingMessage(pendingMessage.localId, (currentMessage) => ({
              ...currentMessage,
              localUploadState: {
                ...currentMessage.localUploadState,
                status: LOCAL_UPLOAD_STATUSES.UPLOAD_IN_PROGRESS,
                progressPercent,
                errorMessage: "",
              },
            }));
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

  const handleSend = () => {
    if (selectedMedia.length > 0) {
      if (messageInput.trim()) {
        setMediaComposerError("Text captions with media are not supported yet. Clear the text box or send the text separately.");
        return;
      }

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
      return;
    }

    if (messageInput.trim()) {
      onSendMessage(messageInput);
      setMessageInput("");
      setMediaComposerError("");
    }
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

  const handleKeyPress = (e) => {
    if (e.key === "Enter" && !e.shiftKey) {
      e.preventDefault();
      handleSend();
    }
  };

  return (
    <div className="chat-area-wrapper">
      <Box className="chat-area-flex-container">
        <ChatAreaHeader chatName={chatName} isConnected={isConnected} onLogout={onLogout} />
        <ChatMessageList
          chatMessagesRef={chatMessagesRef}
          messagesEndRef={messagesEndRef}
          messages={displayMessages}
          username={username}
          isLoading={isLoading}
          isLoadingOlder={isLoadingOlder}
          onScroll={handleMessagesScroll}
          onRetryPendingMessage={handleRetryPendingMedia}
          onCancelPendingMessage={handleCancelPendingMedia}
          onDismissPendingMessage={removePendingMessage}
        />
        <ChatMessageComposer
          messageInput={messageInput}
          onChange={(event) => setMessageInput(event.target.value)}
          onKeyPress={handleKeyPress}
          onSend={handleSend}
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
