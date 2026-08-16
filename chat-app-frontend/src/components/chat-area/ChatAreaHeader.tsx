import React from "react";
import { AppBar, Toolbar, Typography, Box, Chip, Stack, IconButton } from "@mui/material";
import InfoOutlinedIcon from "@mui/icons-material/InfoOutlined";
import "./ChatAreaHeader.css";

interface ChatAreaHeaderProps {
  chatName: string;
  isConnected: boolean;
  onOpenGroupDetails?: () => void;
  showGroupDetailsAction?: boolean;
}

function ChatAreaHeader({
  chatName,
  isConnected,
  onOpenGroupDetails,
  showGroupDetailsAction,
}: ChatAreaHeaderProps) {
  return (
    <div className="chat-area-header-wrapper">
      <AppBar position="static" className="chat-area-header-appbar">
        <Toolbar className="chat-area-header-toolbar">
          <Stack direction="row" spacing={0.5} className="chat-area-header-title-row" sx={{ alignItems: "center" }}>
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
