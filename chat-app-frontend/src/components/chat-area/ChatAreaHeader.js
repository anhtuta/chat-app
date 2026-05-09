import React from "react";
import { AppBar, Toolbar, Typography, Box, Chip, Button } from "@mui/material";
import LogoutIcon from "@mui/icons-material/Logout";
import "./ChatAreaHeader.css";

function ChatAreaHeader({ chatName, isConnected, onLogout }) {
  return (
    <div className="chat-area-header-wrapper">
      <AppBar position="static" className="chat-area-header-appbar">
        <Toolbar className="chat-area-header-toolbar">
          <Typography variant="h6" className="chat-area-header-title">
            {chatName}
          </Typography>
          <Button
            color="inherit"
            startIcon={<LogoutIcon />}
            onClick={onLogout}
            className="chat-area-header-logout-button"
          >
            Logout
          </Button>
        </Toolbar>
      </AppBar>
      <Box className={`chat-area-header-status-box ${isConnected ? "connected" : "disconnected"}`}>
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