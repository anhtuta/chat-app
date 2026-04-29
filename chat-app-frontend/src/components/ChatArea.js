import React, { useState, useEffect, useRef } from "react";
import { Box } from "@mui/material";
import ChatAreaHeader from "./chat-area/ChatAreaHeader";
import ChatMessageList from "./chat-area/ChatMessageList";
import ChatMessageComposer from "./chat-area/ChatMessageComposer";

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
  onLoadOlderMessages,
  onLogout,
}) {
  const [messageInput, setMessageInput] = useState("");
  const chatMessagesRef = useRef(null);
  const messagesEndRef = useRef(null);
  const isPrependingRef = useRef(false);
  const isLoadingOlderRef = useRef(false);
  const prevMessagesRef = useRef([]);
  const forceScrollToBottomRef = useRef(true);

  useEffect(() => {
    isLoadingOlderRef.current = isLoadingOlder;
  }, [isLoadingOlder]);

  useEffect(() => {
    const container = chatMessagesRef.current;
    const prevMessages = prevMessagesRef.current;

    const getMessageKey = (message) => {
      if (!message) return "";
      return `${message.id ?? "no-id"}-${message.timestamp ?? "no-time"}-${message.content ?? ""}`;
    };

    const prevFirstKey = getMessageKey(prevMessages[0]);
    const prevLastKey = getMessageKey(prevMessages[prevMessages.length - 1]);
    const nextFirstKey = getMessageKey(messages[0]);
    const nextLastKey = getMessageKey(messages[messages.length - 1]);

    const grew = messages.length > prevMessages.length;
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
    prevMessagesRef.current = messages;
  }, [messages]);

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

  const handleSend = () => {
    if (messageInput.trim()) {
      onSendMessage(messageInput);
      setMessageInput("");
    }
  };

  const handleKeyPress = (e) => {
    if (e.key === "Enter" && !e.shiftKey) {
      e.preventDefault();
      handleSend();
    }
  };

  return (
    <div className="chat-area-wrapper">
      <Box sx={{ display: "flex", flexDirection: "column", height: "100vh", flex: 1 }}>
        <ChatAreaHeader chatName={chatName} isConnected={isConnected} onLogout={onLogout} />
        <ChatMessageList
          chatMessagesRef={chatMessagesRef}
          messagesEndRef={messagesEndRef}
          messages={messages}
          username={username}
          isLoading={isLoading}
          isLoadingOlder={isLoadingOlder}
          onScroll={handleMessagesScroll}
        />
        <ChatMessageComposer
          messageInput={messageInput}
          onChange={(event) => setMessageInput(event.target.value)}
          onKeyPress={handleKeyPress}
          onSend={handleSend}
        />
      </Box>
    </div>
  );
}

export default ChatArea;
