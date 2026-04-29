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
      <Drawer
        variant="permanent"
        sx={{
          width: 280,
          flexShrink: 0,
          "& .MuiDrawer-paper": {
            width: 280,
            boxSizing: "border-box",
            backgroundColor: "var(--color-surface-soft)",
            borderRight: "1px solid var(--color-border)",
          },
        }}
      >
        <Box sx={{ p: 2 }}>
          <Typography variant="h6" sx={{ fontWeight: "bold", mb: 2 }}>
            💬 Chats
          </Typography>
          <Button
            variant="contained"
            startIcon={<AddIcon />}
            fullWidth
            onClick={onCreateGroupClick}
            sx={{ textTransform: "none" }}
          >
            New Group
          </Button>
          <FormControl fullWidth size="small" sx={{ mt: 1.5 }}>
            <InputLabel id="theme-selector-label">Theme</InputLabel>
            <Select
              labelId="theme-selector-label"
              value={selectedThemeId}
              label="Theme"
              onChange={(event) => onThemeChange(event.target.value)}
            >
              {themeOptions.map((theme) => (
                <MenuItem key={theme.id} value={theme.id}>
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
            sx={{
              backgroundColor: currentChatId === "public" ? "var(--color-selection-bg)" : "transparent",
              "&:hover": {
                backgroundColor: "var(--color-surface-subtle)",
              },
            }}
          >
            <ListItemText
              primary="Public Chat"
              secondary="Everyone"
              primaryTypographyProps={{
                variant: "body2",
                sx: { fontWeight: "500" },
              }}
              secondaryTypographyProps={{
                variant: "caption",
              }}
            />
          </ListItemButton>

          {groups.map((group) => (
            <ListItemButton
              key={group.id}
              selected={currentChatId === group.id}
              onClick={() => onChatSelect(group.id)}
              sx={{
                backgroundColor: currentChatId === group.id ? "var(--color-selection-bg)" : "transparent",
                "&:hover": {
                  backgroundColor: "var(--color-surface-subtle)",
                },
              }}
            >
              <ListItemText
                primary={group.name}
                secondary="Group"
                primaryTypographyProps={{
                  variant: "body2",
                  sx: { fontWeight: "500" },
                }}
                secondaryTypographyProps={{
                  variant: "caption",
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
