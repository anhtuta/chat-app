import React, { useEffect, useMemo, useRef, useState } from "react";
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
import {
  formatMaxMembersInput,
  maxMembersEquals,
  parseMaxMembersInput,
} from "../../utils/groupMemberLimit";
import GroupBannedMemberList from "./GroupBannedMemberList";
import GroupJoinLinksSection from "./GroupJoinLinksSection";
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
  roleChangeSignal?: number;
  profileChangeSignal?: number;
  onClose: () => void;
  onGroupUpdated?: (group: ChatGroup) => void;
  onGroupLeft?: (groupId: number | string) => void;
}

function GroupDetailsDialog({
  open,
  groupId,
  initialGroup,
  currentUsername,
  roleChangeSignal = 0,
  profileChangeSignal = 0,
  onClose,
  onGroupUpdated,
  onGroupLeft,
}: GroupDetailsDialogProps) {
  const [groupDetails, setGroupDetails] = useState<ChatGroup | null>(initialGroup);
  const [groupName, setGroupName] = useState(initialGroup?.name || "");
  const [description, setDescription] = useState(initialGroup?.description || "");
  const [maxMembersInput, setMaxMembersInput] = useState(formatMaxMembersInput(initialGroup?.maxMembers));
  const [isLoading, setIsLoading] = useState(false);
  const [isSaving, setIsSaving] = useState(false);
  const [error, setError] = useState("");
  const [bannedReloadToken, setBannedReloadToken] = useState(0);
  const initialGroupRef = useRef(initialGroup);
  initialGroupRef.current = initialGroup;

  // Seed form when the dialog opens or the selected group changes — not when the
  // parent replaces initialGroup identity (e.g. sidebar refresh), so unsaved edits survive.
  useEffect(() => {
    if (!open) {
      return;
    }
    const seed = initialGroupRef.current;
    setGroupDetails(seed);
    setGroupName(seed?.name || "");
    setDescription(seed?.description || "");
    setMaxMembersInput(formatMaxMembersInput(seed?.maxMembers));
    setError("");
  }, [groupId, open, profileChangeSignal]);

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
        setMaxMembersInput(formatMaxMembersInput(nextGroup.maxMembers));
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

  useEffect(() => {
    if (!open || !initialGroup) {
      return;
    }

    setGroupDetails((previous) => {
      if (!previous || Number(previous.id) !== Number(initialGroup.id)) {
        return previous;
      }
      return {
        ...previous,
        currentUserRole: initialGroup.currentUserRole ?? previous.currentUserRole,
        currentUserPermissions: initialGroup.currentUserPermissions ?? previous.currentUserPermissions,
        unreadCount: initialGroup.unreadCount ?? previous.unreadCount,
      };
    });
  }, [initialGroup, open]);

  const permissions = groupDetails?.currentUserPermissions || [];
  const canManageGroupDetails = permissions.includes("MANAGE_GROUP_DETAILS");
  const canUnbanMembers = permissions.includes("UNBAN_MEMBERS");
  const canManageJoinLinks = permissions.includes("CREATE_JOIN_LINK");
  const normalizedName = groupName.trim();
  const normalizedDescription = description.trim();
  const normalizedDescriptionOrNull = normalizedDescription ? normalizedDescription : null;

  const parsedMaxMembers = parseMaxMembersInput(maxMembersInput);

  const hasChanges = useMemo(() => {
    if (!groupDetails) {
      return false;
    }
    const maxMembersChanged = parsedMaxMembers.ok === false
      || !maxMembersEquals(parsedMaxMembers.value, groupDetails.maxMembers);
    return normalizedName !== (groupDetails.name || "")
      || normalizedDescriptionOrNull !== (groupDetails.description || null)
      || maxMembersChanged;
  }, [groupDetails, normalizedDescriptionOrNull, normalizedName, parsedMaxMembers]);

  const handleSave = async () => {
    if (!groupDetails) {
      return;
    }
    if (!normalizedName) {
      setError("Group name is required");
      return;
    }
    const parsed = parseMaxMembersInput(maxMembersInput);
    if (parsed.ok === false) {
      setError(parsed.message);
      return;
    }

    setIsSaving(true);
    setError("");
    try {
      const updatedGroup = await updateGroupDetails(groupDetails.id, {
        name: normalizedName,
        description: normalizedDescriptionOrNull,
        ...(maxMembersEquals(parsed.value, groupDetails.maxMembers)
          ? {}
          : { maxMembers: parsed.value }),
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
      setMaxMembersInput(formatMaxMembersInput(mergedGroup.maxMembers));
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
                maxMembersInput={maxMembersInput}
                canManageGroupDetails={canManageGroupDetails}
                isSaving={isSaving}
                onGroupNameChange={setGroupName}
                onDescriptionChange={setDescription}
                onMaxMembersChange={setMaxMembersInput}
              />

              <GroupRolePermissionsSection
                currentUserRole={groupDetails?.currentUserRole}
                permissions={permissions}
              />

              <GroupMemberList
                open={open}
                groupId={groupId}
                maxMembers={groupDetails?.maxMembers}
                currentUsername={currentUsername}
                currentUserRole={groupDetails?.currentUserRole}
                currentUserPermissions={permissions}
                roleChangeSignal={roleChangeSignal}
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

              <GroupJoinLinksSection
                open={open}
                groupId={groupId}
                canManageJoinLinks={canManageJoinLinks}
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
