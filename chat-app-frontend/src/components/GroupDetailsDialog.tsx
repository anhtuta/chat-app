import React, { useEffect, useMemo, useState } from "react";
import {
  Alert,
  Box,
  Button,
  Chip,
  CircularProgress,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Stack,
  TextField,
  Typography,
} from "@mui/material";
import { getGroupDetails, updateGroupDetails } from "../services/api";
import type { ChatGroup } from "../types/groups";

interface GroupDetailsDialogProps {
  open: boolean;
  groupId: number | string | null | undefined;
  initialGroup: ChatGroup | null;
  onClose: () => void;
  onGroupUpdated?: (group: ChatGroup) => void;
}

function GroupDetailsDialog({
  open,
  groupId,
  initialGroup,
  onClose,
  onGroupUpdated,
}: GroupDetailsDialogProps) {
  const [groupDetails, setGroupDetails] = useState<ChatGroup | null>(initialGroup);
  const [groupName, setGroupName] = useState(initialGroup?.name || "");
  const [description, setDescription] = useState(initialGroup?.description || "");
  const [isLoading, setIsLoading] = useState(false);
  const [isSaving, setIsSaving] = useState(false);
  const [error, setError] = useState("");

  useEffect(() => {
    if (!open) {
      return;
    }
    setGroupDetails(initialGroup);
    setGroupName(initialGroup?.name || "");
    setDescription(initialGroup?.description || "");
    setError("");
  }, [initialGroup, open]);

  useEffect(() => {
    if (!open || groupId === null || groupId === undefined) {
      return;
    }

    let isCancelled = false;
    setIsLoading(true);
    setError("");

    getGroupDetails(groupId)
      .then((nextGroup) => {
        if (isCancelled) {
          return;
        }
        setGroupDetails(nextGroup);
        setGroupName(nextGroup.name || "");
        setDescription(nextGroup.description || "");
      })
      .catch((nextError: unknown) => {
        if (isCancelled) {
          return;
        }
        console.error("Error loading group details:", nextError);
        setError(toErrorMessage(nextError, "Failed to load group details"));
      })
      .finally(() => {
        if (!isCancelled) {
          setIsLoading(false);
        }
      });

    return () => {
      isCancelled = true;
    };
  }, [groupId, open]);

  const permissions = groupDetails?.currentUserPermissions || [];
  const canManageGroupDetails = permissions.includes("MANAGE_GROUP_DETAILS");
  const normalizedName = groupName.trim();
  const normalizedDescription = description.trim();
  const normalizedDescriptionOrNull = normalizedDescription ? normalizedDescription : null;

  const hasChanges = useMemo(() => {
    if (!groupDetails) {
      return false;
    }
    return normalizedName !== (groupDetails.name || "")
      || normalizedDescriptionOrNull !== (groupDetails.description || null);
  }, [groupDetails, normalizedDescriptionOrNull, normalizedName]);

  const handleSave = async () => {
    if (!groupDetails) {
      return;
    }
    if (!normalizedName) {
      setError("Group name is required");
      return;
    }

    setIsSaving(true);
    setError("");
    try {
      const updatedGroup = await updateGroupDetails(groupDetails.id, {
        name: normalizedName,
        description: normalizedDescriptionOrNull,
      });
      // PATCH returns group metadata only; keep role/permissions/unread from prior state.
      const mergedGroup: ChatGroup = {
        ...groupDetails,
        ...updatedGroup,
        unreadCount: groupDetails.unreadCount,
        currentUserRole: groupDetails.currentUserRole,
        currentUserPermissions: groupDetails.currentUserPermissions,
      };
      setGroupDetails(mergedGroup);
      onGroupUpdated?.(mergedGroup);
      onClose();
    } catch (saveError: unknown) {
      console.error("Error updating group details:", saveError);
      setError(toErrorMessage(saveError, "Failed to update group details"));
    } finally {
      setIsSaving(false);
    }
  };

  return (
    <div className="group-details-dialog-wrapper">
      <Dialog open={open} onClose={isSaving ? undefined : onClose} maxWidth="sm" fullWidth>
        <DialogTitle>Group Details</DialogTitle>
        <DialogContent dividers>
          {error ? (
            <Alert severity="error" sx={{ mb: 2 }}>
              {error}
            </Alert>
          ) : null}

          {isLoading ? (
            <Box sx={{ display: "flex", justifyContent: "center", py: 5 }}>
              <CircularProgress size={28} />
            </Box>
          ) : (
            <Stack spacing={2}>
              <Box>
                <Typography variant="subtitle2" sx={{ mb: 1 }}>
                  Your role
                </Typography>
                <Chip label={groupDetails?.currentUserRole || "MEMBER"} size="small" />
              </Box>

              <Box>
                <Typography variant="subtitle2" sx={{ mb: 1 }}>
                  Granted permissions
                </Typography>
                {permissions.length ? (
                  <Box sx={{ display: "flex", flexWrap: "wrap", gap: 1 }}>
                    {permissions.map((permission) => (
                      <Chip key={permission} label={permission} size="small" variant="outlined" />
                    ))}
                  </Box>
                ) : (
                  <Typography variant="body2" color="text.secondary">
                    No explicit permissions available.
                  </Typography>
                )}
              </Box>

              <TextField
                label="Group name"
                value={groupName}
                onChange={(event) => setGroupName(event.target.value)}
                fullWidth
                required
                disabled={!canManageGroupDetails || isSaving}
              />

              <TextField
                label="Description"
                value={description}
                onChange={(event) => setDescription(event.target.value)}
                fullWidth
                multiline
                minRows={3}
                disabled={!canManageGroupDetails || isSaving}
                helperText={canManageGroupDetails ? "Leave blank to clear the description." : "Read-only"}
              />
            </Stack>
          )}
        </DialogContent>
        <DialogActions>
          <Button onClick={onClose} disabled={isSaving}>
            Close
          </Button>
          {canManageGroupDetails ? (
            <Button
              onClick={handleSave}
              variant="contained"
              disabled={isLoading || isSaving || !normalizedName || !hasChanges}
            >
              {isSaving ? "Saving..." : "Save"}
            </Button>
          ) : null}
        </DialogActions>
      </Dialog>
    </div>
  );
}

function toErrorMessage(error: unknown, fallbackMessage: string): string {
  return error instanceof Error ? error.message : fallbackMessage;
}

export default GroupDetailsDialog;
