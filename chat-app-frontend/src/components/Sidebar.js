import React from "react";
import {
  Box,
  Drawer,
  List,
  ListItem,
  ListItemText,
  ListItemButton,
  Button,
  Typography,
  Divider,
} from "@mui/material";
import AddIcon from "@mui/icons-material/Add";

function Sidebar({ groups, currentChatId, onChatSelect, onCreateGroupClick }) {
  return (
    <Drawer
      variant="permanent"
      sx={{
        width: 280,
        flexShrink: 0,
        "& .MuiDrawer-paper": {
          width: 280,
          boxSizing: "border-box",
          backgroundColor: "#f5f5f5",
          borderRight: "1px solid #e0e0e0",
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
      </Box>
      <Divider />
      <List>
        {/* Public Chat Item */}
        <ListItemButton
          selected={currentChatId === "public"}
          onClick={() => onChatSelect("public")}
          sx={{
            backgroundColor:
              currentChatId === "public" ? "#e3f2fd" : "transparent",
            "&:hover": {
              backgroundColor: "#f0f0f0",
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

        {/* Group Items */}
        {groups.map((group) => (
          <ListItemButton
            key={group.id}
            selected={currentChatId === group.id}
            onClick={() => onChatSelect(group.id)}
            sx={{
              backgroundColor:
                currentChatId === group.id ? "#e3f2fd" : "transparent",
              "&:hover": {
                backgroundColor: "#f0f0f0",
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
  );
}

export default Sidebar;
