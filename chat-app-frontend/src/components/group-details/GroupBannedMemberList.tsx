import React, { useEffect, useRef, useState } from "react";
import {
  Alert,
  Box,
  Button,
  CircularProgress,
  Dialog,
  DialogActions,
  DialogContent,
  DialogContentText,
  DialogTitle,
  IconButton,
  List,
  ListItem,
  ListItemText,
  Typography,
} from "@mui/material";
import LockOpenOutlinedIcon from "@mui/icons-material/LockOpenOutlined";
import { getGroupBans, unbanGroupMember } from "../../services/api";
import type { GroupBan } from "../../types/groups";
import { formatAbsoluteTimeVi } from "../../utils/dateUtils";

interface GroupBannedMemberListProps {
  groupId: number | string | null | undefined;
  open: boolean;
  canUnban: boolean;
  reloadToken?: number;
}

function GroupBannedMemberList({
  groupId,
  open,
  canUnban,
  reloadToken = 0,
}: GroupBannedMemberListProps) {
  const [bans, setBans] = useState<GroupBan[]>([]);
  const [isLoading, setIsLoading] = useState(false);
  const [error, setError] = useState("");
  const [pendingUnban, setPendingUnban] = useState<GroupBan | null>(null);
  const [isUnbanning, setIsUnbanning] = useState(false);
  const requestIdRef = useRef(0);

  useEffect(() => {
    if (!open || !canUnban || groupId === null || groupId === undefined) {
      return;
    }

    let isCancelled = false;
    const requestId = ++requestIdRef.current;
    setIsLoading(true);
    setError("");

    getGroupBans(groupId)
      .then((nextBans) => {
        if (isCancelled || requestId !== requestIdRef.current) {
          return;
        }
        setBans(nextBans || []);
      })
      .catch((nextError: unknown) => {
        if (isCancelled || requestId !== requestIdRef.current) {
          return;
        }
        console.error("Error loading banned users:", nextError);
        setError(toErrorMessage(nextError, "Failed to load banned users"));
      })
      .finally(() => {
        if (!isCancelled && requestId === requestIdRef.current) {
          setIsLoading(false);
        }
      });

    return () => {
      isCancelled = true;
    };
  }, [canUnban, groupId, open, reloadToken]);

  useEffect(() => {
    if (!open) {
      setPendingUnban(null);
      setError("");
    }
  }, [open]);

  if (!canUnban || groupId === null || groupId === undefined) {
    return null;
  }

  const handleConfirmUnban = async () => {
    if (!pendingUnban) {
      return;
    }

    setIsUnbanning(true);
    setError("");
    try {
      await unbanGroupMember(groupId, pendingUnban.userId);
      setBans((previous) => previous.filter((ban) => ban.userId !== pendingUnban.userId));
      setPendingUnban(null);
    } catch (unbanError: unknown) {
      console.error("Error unbanning group member:", unbanError);
      setError(toErrorMessage(unbanError, "Failed to unban group member"));
    } finally {
      setIsUnbanning(false);
    }
  };

  const pendingDisplayName = pendingUnban?.fullname?.trim() || pendingUnban?.username || "user";

  return (
    <div className="group-banned-member-list-wrapper group-details-section">
      <Typography variant="subtitle1" className="group-details-section-title">
        Banned users
      </Typography>

      {error ? (
        <Alert severity="error" sx={{ mb: 1.5 }}>
          {error}
        </Alert>
      ) : null}

      {isLoading ? (
        <Box sx={{ display: "flex", justifyContent: "center", py: 3 }}>
          <CircularProgress size={24} />
        </Box>
      ) : (
        <Box className="group-details-member-scroll">
          <List dense disablePadding>
            {bans.map((ban) => {
              const displayName = ban.fullname?.trim() || ban.username;
              return (
                <ListItem
                  key={ban.userId}
                  alignItems="flex-start"
                  className="group-details-member-item"
                  sx={{ px: 1.5, py: 1.25, pr: 8 }}
                  secondaryAction={
                    <IconButton
                      edge="end"
                      title={`Unban ${displayName}`}
                      aria-label={`Unban ${displayName}`}
                      onClick={() => setPendingUnban(ban)}
                      size="small"
                      disabled={isUnbanning}
                    >
                      <LockOpenOutlinedIcon fontSize="small" />
                    </IconButton>
                  }
                >
                  <ListItemText
                    primary={
                      <Typography variant="body2" sx={{ fontWeight: 600 }}>
                        {displayName}
                      </Typography>
                    }
                    secondary={
                      <Box>
                        <Typography variant="body2" className="group-details-muted-text">
                          @{ban.username}
                        </Typography>
                        <Typography variant="caption" className="group-details-muted-text" sx={{ display: "block" }}>
                          Banned {formatAbsoluteTimeVi(ban.bannedAt)}
                          {ban.bannedByUsername ? ` by @${ban.bannedByUsername}` : ""}
                        </Typography>
                        {ban.reason ? (
                          <Typography variant="caption" className="group-details-muted-text" sx={{ display: "block" }}>
                            Reason: {ban.reason}
                          </Typography>
                        ) : null}
                      </Box>
                    }
                  />
                </ListItem>
              );
            })}
            {!bans.length ? (
              <Typography variant="body2" className="group-details-muted-text" sx={{ px: 1.5, py: 2 }}>
                No banned users.
              </Typography>
            ) : null}
          </List>
        </Box>
      )}

      <Dialog open={Boolean(pendingUnban)} onClose={isUnbanning ? undefined : () => setPendingUnban(null)}>
        <DialogTitle>Unban user?</DialogTitle>
        <DialogContent>
          <DialogContentText>
            Unban {pendingDisplayName}
            {pendingUnban ? ` (@${pendingUnban.username})` : ""}? They will be able to rejoin if invited or
            given a valid join link.
          </DialogContentText>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setPendingUnban(null)} disabled={isUnbanning}>
            Cancel
          </Button>
          <Button onClick={handleConfirmUnban} variant="contained" disabled={isUnbanning}>
            {isUnbanning ? "Unbanning..." : "Unban"}
          </Button>
        </DialogActions>
      </Dialog>
    </div>
  );
}

function toErrorMessage(error: unknown, fallbackMessage: string): string {
  return error instanceof Error ? error.message : fallbackMessage;
}

export default GroupBannedMemberList;
