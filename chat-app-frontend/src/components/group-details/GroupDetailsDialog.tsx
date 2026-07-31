import React, { useEffect, useMemo, useState } from "react";
import {
  Alert,
  Box,
  Button,
  CircularProgress,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
} from "@mui/material";
import { getGroupDetails, updateGroupDetails } from "../../services/api";
import type { ChatGroup } from "../../types/groups";
import GroupBannedMemberList from "./GroupBannedMemberList";
import GroupMemberList from "./GroupMemberList";
import GroupProfileSection from "./GroupProfileSection";
import GroupRolePermissionsSection from "./GroupRolePermissionsSection";
import LeaveGroupSection from "./LeaveGroupSection";
import "./GroupDetailsDialog.css";

interface GroupDetailsDialogProps {
  open: boolean;
  groupId: number | string | null | undefined;
  initialGroup: ChatGroup | null;
  currentUsername?: string | null;
  onClose: () => void;
  onGroupUpdated?: (group: ChatGroup) => void;
  onGroupLeft?: (groupId: number | string) => void;
}

function GroupDetailsDialog({
  open,
  groupId,
  initialGroup,
  currentUsername,
  onClose,
  onGroupUpdated,
  onGroupLeft,
}: GroupDetailsDialogProps) {
  const [groupDetails, setGroupDetails] = useState<ChatGroup | null>(initialGroup);
  const [groupName, setGroupName] = useState(initialGroup?.name || "");
  const [description, setDescription] = useState(initialGroup?.description || "");
  const [isLoading, setIsLoading] = useState(false);
  const [isSaving, setIsSaving] = useState(false);
  const [error, setError] = useState("");
  const [bannedReloadToken, setBannedReloadToken] = useState(0);

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
        // Detail API omits authoritative unread; keep the list/sidebar value already in local state.
        setGroupDetails((previous) => ({
          ...nextGroup,
          unreadCount: previous?.unreadCount,
        }));
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
  const canUnbanMembers = permissions.includes("UNBAN_MEMBERS");
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

  const refreshAccessAfterLeadershipTransfer = async () => {
    if (groupId === null || groupId === undefined) {
      return;
    }

    try {
      const nextGroup = await getGroupDetails(groupId);
      const mergedGroup: ChatGroup = {
        ...nextGroup,
        unreadCount: groupDetails?.unreadCount,
      };
      setGroupDetails(mergedGroup);
      onGroupUpdated?.(mergedGroup);
    } catch (refreshError: unknown) {
      console.error("Error refreshing group details after leadership transfer:", refreshError);
      setError(toErrorMessage(refreshError, "Failed to refresh group access after transfer"));
    }
  };

  return (
    <div className="group-details-dialog-wrapper">
      <Dialog
        open={open}
        onClose={isSaving ? undefined : onClose}
        maxWidth="md"
        fullWidth
        className="group-details-dialog"
        slotProps={{
          paper: { className: "group-details-dialog-paper" },
        }}
      >
        <DialogTitle className="group-details-dialog-title">Group Details</DialogTitle>
        <DialogContent dividers className="group-details-dialog-content">
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
            <div className="group-details-sections">
              <GroupProfileSection
                groupName={groupName}
                description={description}
                canManageGroupDetails={canManageGroupDetails}
                isSaving={isSaving}
                onGroupNameChange={setGroupName}
                onDescriptionChange={setDescription}
              />

              <GroupRolePermissionsSection
                currentUserRole={groupDetails?.currentUserRole}
                permissions={permissions}
              />

              <GroupMemberList
                open={open}
                groupId={groupId}
                currentUsername={currentUsername}
                currentUserRole={groupDetails?.currentUserRole}
                currentUserPermissions={permissions}
                onMemberBanned={() => setBannedReloadToken((previous) => previous + 1)}
                onLeadershipTransferred={() => {
                  void refreshAccessAfterLeadershipTransfer();
                }}
              />

              <GroupBannedMemberList
                open={open}
                groupId={groupId}
                canUnban={canUnbanMembers}
                reloadToken={bannedReloadToken}
              />

              {groupId !== null && groupId !== undefined ? (
                <LeaveGroupSection
                  groupId={groupId}
                  groupName={groupDetails?.name || groupName}
                  currentUserRole={groupDetails?.currentUserRole}
                  disabled={isSaving}
                  onLeft={() => onGroupLeft?.(groupId)}
                />
              ) : null}
            </div>
          )}
        </DialogContent>
        <DialogActions className="group-details-dialog-actions">
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
