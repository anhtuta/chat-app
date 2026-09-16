import React from "react";
import { Avatar, Box, IconButton, Typography } from "@mui/material";
import PersonOutlinedIcon from "@mui/icons-material/PersonOutlined";
import LogoutIcon from "@mui/icons-material/Logout";
import "./SidebarUserDetails.css";

interface SidebarUserDetailsProps {
  username?: string | null;
  fullname?: string | null;
  onLogout: () => void | Promise<void>;
}

function SidebarUserDetails({ username, fullname, onLogout }: SidebarUserDetailsProps) {
  const displayUsername = username?.trim() || "unknown";
  const displayFullname = fullname?.trim() || null;

  return (
    <div className="sidebar-user-details-wrapper">
      <Box className="sidebar-user-details">
        <Avatar className="sidebar-user-details-avatar" aria-hidden="true">
          <PersonOutlinedIcon fontSize="small" />
        </Avatar>
        <Box className="sidebar-user-details-text">
          <Typography
            variant="caption"
            className="sidebar-user-details-fullname"
            title={displayFullname || undefined}
          >
            {displayFullname || "\u00A0"}
          </Typography>
          <Typography variant="body2" className="sidebar-user-details-username" title={`@${displayUsername}`}>
            @{displayUsername}
          </Typography>
        </Box>
        <IconButton
          className="sidebar-user-details-logout"
          onClick={onLogout}
          title="Logout"
          aria-label="Logout"
          size="small"
        >
          <LogoutIcon fontSize="small" />
        </IconButton>
      </Box>
    </div>
  );
}

export default SidebarUserDetails;
