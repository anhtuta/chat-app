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
import LinkOutlinedIcon from "@mui/icons-material/LinkOutlined";
import type { ChatGroup } from "../types/groups";
import type { ThemeId, ThemeOption } from "../types/theme";
import "./Sidebar.css";
import { useRef, useLayoutEffect } from "react";

type ChatRouteId = "public" | number;

interface SidebarProps {
  groups: ChatGroup[];
  totalUnreadCount?: number | null;
  currentChatId: ChatRouteId;
  onChatSelect: (chatId: ChatRouteId) => void;
  onCreateGroupClick: () => void;
  onJoinGroupClick: () => void;
  selectedThemeId: ThemeId;
  onThemeChange: (themeId: ThemeId) => void;
  themeOptions: ThemeOption[];
}

function Sidebar({
  groups,
  totalUnreadCount,
  currentChatId,
  onChatSelect,
  onCreateGroupClick,
  onJoinGroupClick,
  selectedThemeId,
  onThemeChange,
  themeOptions,
}: SidebarProps) {
  const itemRefs = useRef(new Map<string, HTMLElement>());
  const prevPositions = useRef<Map<string, DOMRect> | null>(null);

  // FLIP animation: measure previous and new positions and animate translateY
  useLayoutEffect(() => {
    const newPositions = new Map();
    // capture new positions
    groups.forEach((group) => {
      const el = itemRefs.current.get(String(group.id));
      if (el) {
        newPositions.set(String(group.id), el.getBoundingClientRect());
      }
    });

    const prev = prevPositions.current;
    if (prev) {
      groups.forEach((group) => {
        const id = String(group.id);
        const el = itemRefs.current.get(id);
        const prevRect = prev.get(id);
        const newRect = newPositions.get(id);
        if (el && prevRect && newRect) {
          const deltaY = prevRect.top - newRect.top;
          if (deltaY) {
            // apply inverse transform to start at previous position
            el.style.transition = "none";
            el.style.transform = `translateY(${deltaY}px)`;
            // then animate to natural position
            requestAnimationFrame(() => {
              el.style.transition = "transform 300ms cubic-bezier(.2,.8,.2,1)";
              el.style.transform = "";
            });
            const cleanup = () => {
              el.style.transition = "";
              el.style.transform = "";
              el.removeEventListener("transitionend", cleanup);
            };
            el.addEventListener("transitionend", cleanup);
          }
        }
      });
    }

    // store positions for next render
    prevPositions.current = newPositions;
  }, [groups]);
  return (
    <div className="sidebar-wrapper">
      <Drawer variant="permanent">
        <Box className="sidebar-header-box">
          <Typography variant="h6" className="sidebar-title">
            💬 Chats
            {totalUnreadCount && Number(totalUnreadCount) > 0 && (
              <span className="sidebar-total-unread-badge"> ({totalUnreadCount})</span>
            )}
          </Typography>
          <Button
            variant="contained"
            startIcon={<AddIcon />}
            fullWidth
            onClick={onCreateGroupClick}
            className="new-group-button"
            title="Create group"
          >
            New Group
          </Button>
          <Button
            variant="outlined"
            startIcon={<LinkOutlinedIcon />}
            fullWidth
            onClick={onJoinGroupClick}
            className="join-group-button"
            title="Join group with link"
            sx={{ mt: 1 }}
          >
            Join with link
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
              onChange={(event) => onThemeChange(event.target.value as ThemeId)}
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
        <List className="sidebar-group-list">
          <ListItemButton
            selected={currentChatId === "public"}
            onClick={() => onChatSelect("public")}
            className="sidebar-group-item"
            ref={(el) => {
              const id = "public";
              if (el) itemRefs.current.set(id, el);
              else itemRefs.current.delete(id);
            }}
          >
            <ListItemText
              primary="Public Chat"
              secondary="Everyone"
              primaryTypographyProps={{
                variant: "body2",
                className: "sidebar-group-item-primary-text",
              }}
              secondaryTypographyProps={{
                variant: "caption",
                className: "sidebar-group-item-secondary-text",
              }}
            />
          </ListItemButton>

          {groups.map((group) => (
            <ListItemButton
              key={group.id}
              selected={currentChatId === group.id}
              onClick={() => onChatSelect(group.id)}
              className="sidebar-group-item"
              ref={(el) => {
                const id = String(group.id);
                if (el) itemRefs.current.set(id, el);
                else itemRefs.current.delete(id);
              }}
            >
              <ListItemText
                primary={group.name}
                secondary={group.latestMessage ? `${group.latestMessageSender}: ${group.latestMessage}` : "No messages"}
                primaryTypographyProps={{
                  variant: "body2",
                  className: "sidebar-group-item-primary-text",
                }}
                secondaryTypographyProps={{
                  variant: "caption",
                  className: "sidebar-group-item-secondary-text",
                }}
              />
              {group.unreadCount ? (
                <span className="sidebar-badge">{group.unreadCount}</span>
              ) : null}
            </ListItemButton>
          ))}
        </List>
      </Drawer>
    </div>
  );
}

export default Sidebar;
