import React from "react";
import { AppBar, Toolbar, Typography, Box, Chip, Button } from "@mui/material";
import LogoutIcon from "@mui/icons-material/Logout";

function ChatAreaHeader({ chatName, isConnected, onLogout }) {
  return (
    <div className="chat-area-header-wrapper">
      <AppBar position="static" sx={{ backgroundColor: "var(--color-primary)" }}>
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
      <Box
        sx={{
          p: 1,
          backgroundColor: isConnected ? "var(--color-status-success-bg)" : "var(--color-status-error-bg)",
        }}
      >
        <Chip
          label={isConnected ? "🟢 Connected" : "🔴 Disconnected"}
          variant="outlined"
          size="small"
          color={isConnected ? "success" : "error"}
        />
      </Box>
    </div>
  );
}

export default ChatAreaHeader;