import React from "react";
import { Box, TextField, Button, Stack, InputAdornment } from "@mui/material";
import SendIcon from "@mui/icons-material/Send";
import "./ChatMessageComposer.css";

function ChatMessageComposer({ messageInput, onChange, onKeyPress, onSend }) {
  return (
    <div className="chat-message-composer-wrapper">
      <Box className="chat-message-composer-container">
        <Stack direction="row" spacing={1}>
          <TextField
            className="chat-message-input"
            fullWidth
            placeholder="Type a message..."
            value={messageInput}
            onChange={onChange}
            onKeyDown={onKeyPress}
            variant="outlined"
            size="small"
            multiline
            maxRows={3}
            InputProps={{
              endAdornment: (
                <InputAdornment position="end">
                  <Button
                    onClick={onSend}
                    disabled={!messageInput.trim()}
                    startIcon={<SendIcon />}
                    sx={{ minWidth: "auto" }}
                  />
                </InputAdornment>
              ),
            }}
          />
          <Button
            className="chat-send-button"
            variant="contained"
            onClick={onSend}
            disabled={!messageInput.trim()}
            endIcon={<SendIcon />}
          >
            Send
          </Button>
        </Stack>
      </Box>
    </div>
  );
}

export default ChatMessageComposer;