import React from "react";
import {
  Drawer,
  List,
  ListItemText,
  ListItemButton,
  Button,
  Typography,
  Divider,
  Box,
  FormControl,
  InputLabel,
  Select,
  MenuItem,
} from "@mui/material";
import AddIcon from "@mui/icons-material/Add";
import "./Sidebar.css";

function Sidebar({
  groups,
  currentChatId,
  onChatSelect,
  onCreateGroupClick,
  selectedThemeId,
  onThemeChange,
  themeOptions,
}) {
  return (
    <div className="sidebar-wrapper">
      <Drawer variant="permanent">
        <Box className="sidebar-header-box">
          <Typography variant="h6" className="sidebar-title">
            💬 Chats
          </Typography>
          <Button
            variant="contained"
            startIcon={<AddIcon />}
            fullWidth
            onClick={onCreateGroupClick}
            className="new-group-button"
          >
            New Group
          </Button>
          <FormControl fullWidth size="small" className="theme-selector-form">
            <InputLabel id="theme-selector-label" className="theme-selector-label">
              Theme
            </InputLabel>
            {/* MUI's Select dropdown menu renders as a "portal" (outside the normal DOM tree), so CSS selectors targeting it don't work.
                That's why we use the `sx` prop to apply styles directly. */}
            <Select
              labelId="theme-selector-label"
              value={selectedThemeId}
              label="Theme"
              onChange={(event) => onThemeChange(event.target.value)}
              className="theme-selector-select"
              MenuProps={{
                sx: {
                  "& .MuiMenu-paper": {
                    backgroundColor: "var(--color-surface)",
                    color: "var(--color-text-primary)",
                  },
                  "& .MuiMenuItem-root": {
                    color: "var(--color-text-primary)",
                    backgroundColor: "var(--color-surface)",
                    "&:hover": {
                      backgroundColor: "var(--color-surface-hover)",
                    },
                    "&.Mui-selected": {
                      backgroundColor: "var(--color-selection-bg)",
                    },
                  },
                },
              }}
            >
              {themeOptions.map((theme) => (
                <MenuItem key={theme.id} value={theme.id} className="theme-selector-menu-item">
                  {theme.label}
                </MenuItem>
              ))}
            </Select>
          </FormControl>
        </Box>
        <Divider />
        <List>
          <ListItemButton
            selected={currentChatId === "public"}
            onClick={() => onChatSelect("public")}
            className="chat-list-item"
          >
            <ListItemText
              primary="Public Chat"
              secondary="Everyone"
              primaryTypographyProps={{
                variant: "body2",
                className: "chat-list-item-primary-text",
                sx: { color: "var(--color-text-primary)" },
              }}
              secondaryTypographyProps={{
                variant: "caption",
                className: "chat-list-item-secondary-text",
                sx: { color: "var(--color-text-secondary)" },
              }}
            />
          </ListItemButton>

          {groups.map((group) => (
            <ListItemButton
              key={group.id}
              selected={currentChatId === group.id}
              onClick={() => onChatSelect(group.id)}
              className="chat-list-item"
            >
              <ListItemText
                primary={group.name}
                secondary={group.latestMessage ? `${group.latestMessageSender}: ${group.latestMessage}` : "No messages"}
                primaryTypographyProps={{
                  variant: "body2",
                  className: "chat-list-item-primary-text",
                  sx: { color: "var(--color-text-primary)" },
                }}
                secondaryTypographyProps={{
                  variant: "caption",
                  className: "chat-list-item-secondary-text",
                  sx: { color: "var(--color-text-secondary)" },
                }}
              />
            </ListItemButton>
          ))}
        </List>
      </Drawer>
    </div>
  );
}

export default Sidebar;
