import React, { useState, useEffect, useRef } from "react";
import {
  AppBar,
  Toolbar,
  Typography,
  Box,
  Paper,
  TextField,
  Button,
  Chip,
  CircularProgress,
  Stack,
  InputAdornment,
} from "@mui/material";
import SendIcon from "@mui/icons-material/Send";
import LogoutIcon from "@mui/icons-material/Logout";

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

  const formatMessage = (message) => {
    const isSystemMessage = message.content && message.content.startsWith("[SYSTEM] ");

    if (isSystemMessage) {
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

    return {
      type: isOwnMessage ? "sent" : "received",
      displayName,
      content: message.content,
      timestamp: message.timestamp,
    };
  };

  return (
    <Box sx={{ display: "flex", flexDirection: "column", height: "100vh", flex: 1 }}>
      {/* Header */}
      <AppBar position="static" sx={{ backgroundColor: "#667eea" }}>
        <Toolbar sx={{ justifyContent: "space-between" }}>
          <Typography variant="h6" sx={{ fontWeight: "bold" }}>
            {chatName}
          </Typography>
          <Button
            color="inherit"
            startIcon={<LogoutIcon />}
            onClick={onLogout}
            sx={{ textTransform: "none" }}
          >
            Logout
          </Button>
        </Toolbar>
      </AppBar>

      {/* Status */}
      <Box sx={{ p: 1, backgroundColor: isConnected ? "#c8e6c9" : "#ffcdd2" }}>
        <Chip
          label={isConnected ? "🟢 Connected" : "🔴 Disconnected"}
          variant="outlined"
          size="small"
          color={isConnected ? "success" : "error"}
        />
      </Box>

      {/* Loading Indicator */}
      {isLoading && (
        <Box sx={{ p: 2, textAlign: "center" }}>
          <CircularProgress size={24} sx={{ mr: 1 }} />
          Loading previous messages
        </Box>
      )}

      {/* Messages Container */}
      <Box
        ref={chatMessagesRef}
        onScroll={handleMessagesScroll}
        sx={{
          flex: 1,
          overflowY: "auto",
          p: 2,
          backgroundColor: "#fafafa",
        }}
      >
        {isLoadingOlder && (
          <Box sx={{ textAlign: "center", py: 2 }}>
            <CircularProgress size={24} />
            <Typography variant="body2" color="textSecondary" sx={{ mt: 1 }}>
              Loading older messages...
            </Typography>
          </Box>
        )}

        {messages.map((message, index) => {
          const formatted = formatMessage(message);

          if (formatted.type === "system") {
            return (
              <Box
                key={index}
                sx={{
                  textAlign: "center",
                  py: 1.5,
                  px: 2,
                  my: 1,
                  backgroundColor: formatted.isDisconnected
                    ? "#ffebee"
                    : formatted.isConnected
                      ? "#e8f5e9"
                      : "#f5f5f5",
                  borderRadius: 1,
                }}
              >
                <Typography
                  variant="caption"
                  sx={{
                    color: formatted.isDisconnected
                      ? "#c62828"
                      : formatted.isConnected
                        ? "#2e7d32"
                        : "#666",
                    fontWeight: 500,
                  }}
                >
                  {formatted.content}
                </Typography>
              </Box>
            );
          }

          const isOwnMessage = formatted.type === "sent";

          return (
            <Box
              key={message.id || index}
              sx={{
                display: "flex",
                justifyContent: isOwnMessage ? "flex-end" : "flex-start",
                mb: 1.5,
              }}
            >
              <Paper
                sx={{
                  maxWidth: "60%",
                  p: 1.5,
                  backgroundColor: isOwnMessage ? "#667eea" : "#e0e0e0",
                  color: isOwnMessage ? "white" : "black",
                  borderRadius: 2,
                }}
              >
                <Typography
                  variant="caption"
                  sx={{
                    display: "block",
                    fontWeight: 600,
                    mb: 0.5,
                    opacity: isOwnMessage ? 0.9 : 1,
                  }}
                >
                  {formatted.displayName}
                </Typography>
                <Typography variant="body2" sx={{ mb: 0.5 }}>
                  {formatted.content}
                </Typography>
                <Typography
                  variant="caption"
                  sx={{
                    display: "block",
                    opacity: isOwnMessage ? 0.8 : 0.7,
                  }}
                >
                  {new Date(formatted.timestamp).toLocaleTimeString([], {
                    hour: "2-digit",
                    minute: "2-digit",
                  })}{" "}
                  {new Date(formatted.timestamp).toLocaleDateString()}
                </Typography>
              </Paper>
            </Box>
          );
        })}

        <div ref={messagesEndRef} />
      </Box>

      {/* Input Area */}
      <Box
        sx={{
          p: 2,
          backgroundColor: "white",
          borderTop: "1px solid #e0e0e0",
        }}
      >
        <Stack direction="row" spacing={1}>
          <TextField
            fullWidth
            placeholder="Type a message..."
            value={messageInput}
            onChange={(e) => setMessageInput(e.target.value)}
            onKeyPress={handleKeyPress}
            variant="outlined"
            size="small"
            multiline
            maxRows={3}
            InputProps={{
              endAdornment: (
                <InputAdornment position="end">
                  <Button
                    onClick={handleSend}
                    disabled={!messageInput.trim()}
                    startIcon={<SendIcon />}
                    sx={{ minWidth: "auto" }}
                  />
                </InputAdornment>
              ),
            }}
          />
          <Button
            variant="contained"
            onClick={handleSend}
            disabled={!messageInput.trim()}
            endIcon={<SendIcon />}
            sx={{ px: 3 }}
          >
            Send
          </Button>
        </Stack>
      </Box>
    </Box>
  );
}

export default ChatArea;
