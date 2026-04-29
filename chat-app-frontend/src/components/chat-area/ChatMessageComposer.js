import React from "react";
import { Box, TextField, Button, Stack, InputAdornment } from "@mui/material";
import SendIcon from "@mui/icons-material/Send";

function ChatMessageComposer({ messageInput, onChange, onKeyPress, onSend }) {
  return (
    <div className="chat-message-composer-wrapper">
      <Box
        sx={{
          p: 2,
          backgroundColor: "var(--color-surface)",
          borderTop: "1px solid var(--color-border)",
        }}
      >
        <Stack direction="row" spacing={1}>
          <TextField
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
            variant="contained"
            onClick={onSend}
            disabled={!messageInput.trim()}
            endIcon={<SendIcon />}
            sx={{ px: 3 }}
          >
            Send
          </Button>
        </Stack>
      </Box>
    </div>
  );
}

export default ChatMessageComposer;