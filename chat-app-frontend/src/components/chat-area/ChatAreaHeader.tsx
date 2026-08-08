import React from "react";
import { AppBar, Toolbar, Typography, Box, Chip, Button, Stack, IconButton } from "@mui/material";
import LogoutIcon from "@mui/icons-material/Logout";
import InfoOutlinedIcon from "@mui/icons-material/InfoOutlined";
import "./ChatAreaHeader.css";

interface ChatAreaHeaderProps {
  chatName: string;
  isConnected: boolean;
  onLogout: () => void;
  onOpenGroupDetails?: () => void;
  showGroupDetailsAction?: boolean;
}

function ChatAreaHeader({
  chatName,
  isConnected,
  onLogout,
  onOpenGroupDetails,
  showGroupDetailsAction,
}: ChatAreaHeaderProps) {
  return (
    <div className="chat-area-header-wrapper">
      <AppBar position="static" className="chat-area-header-appbar">
        <Toolbar className="chat-area-header-toolbar">
          <Stack direction="row" spacing={0.5} alignItems="center" className="chat-area-header-title-row">
            <Typography variant="h6" className="chat-area-header-title">
              {chatName}
            </Typography>
            {showGroupDetailsAction ? (
              <IconButton
                color="inherit"
                size="small"
                onClick={onOpenGroupDetails}
                title="Group details"
                aria-label="Group details"
                className="chat-area-header-details-button"
              >
                <InfoOutlinedIcon fontSize="small" />
              </IconButton>
            ) : null}
          </Stack>
          <Stack direction="row" spacing={1} alignItems="center">
            <Button
              color="inherit"
              startIcon={<LogoutIcon />}
              onClick={onLogout}
              className="chat-area-header-logout-button"
            >
              Logout
            </Button>
          </Stack>
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
