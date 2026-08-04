import React, { useMemo, useState } from "react";
import {
  Box,
  Button,
  Chip,
  Dialog,
  DialogActions,
  DialogContent,
  DialogContentText,
  DialogTitle,
  FormControl,
  IconButton,
  InputLabel,
  ListItem,
  ListItemText,
  MenuItem,
  Select,
  TextField,
  Typography,
} from "@mui/material";
import BlockOutlinedIcon from "@mui/icons-material/BlockOutlined";
import KickOutOutlinedIcon from "@mui/icons-material/PersonRemoveOutlined";
import ManageAccountsOutlinedIcon from "@mui/icons-material/ManageAccountsOutlined";
import SwapHorizOutlinedIcon from "@mui/icons-material/SwapHorizOutlined";
import type { GroupMember, GroupRole } from "../../types/groups";
import {
  canPreviewManageTarget,
  formatGroupRoleLabel,
  getAssignableGroupRoles,
  normalizeGroupRole,
} from "../../utils/groupRoles";
import { GROUP_ROLES } from "../../constant/groupRoles";
import { formatAbsoluteTimeVi } from "../../utils/dateUtils";
import {
  banGroupMember,
  kickGroupMember,
  transferGroupLeadership,
  updateGroupMemberRole,
} from "../../services/api";
import { groupDetailsTextFieldSx } from "./groupDetailsFieldSx";

interface GroupMemberListItemProps {
  groupId: number | string;
  member: GroupMember;
  currentUsername?: string | null;
  currentUserRole?: string | null;
  currentUserPermissions?: string[];
  onMemberKickedOut?: (userId: number) => void;
  onMemberBanned?: (userId: number) => void;
  onMemberRoleUpdated?: (member: GroupMember) => void;
  onLeadershipTransferred?: (newLeaderUserId: number) => void;
  onError?: (message: string) => void;
}

function GroupMemberListItem({
  groupId,
  member,
  currentUsername,
  currentUserRole,
  currentUserPermissions = [],
  onMemberKickedOut,
  onMemberBanned,
  onMemberRoleUpdated,
  onLeadershipTransferred,
  onError,
}: GroupMemberListItemProps) {
  const [kickConfirmOpen, setKickConfirmOpen] = useState(false);
  const [banConfirmOpen, setBanConfirmOpen] = useState(false);
  const [roleDialogOpen, setRoleDialogOpen] = useState(false);
  const [transferConfirmOpen, setTransferConfirmOpen] = useState(false);
  const [banReason, setBanReason] = useState("");
  const [selectedRole, setSelectedRole] = useState<GroupRole>(normalizeGroupRole(member.role));
  const [isKickingOut, setIsKickingOut] = useState(false);
  const [isBanning, setIsBanning] = useState(false);
  const [isUpdatingRole, setIsUpdatingRole] = useState(false);
  const [isTransferring, setIsTransferring] = useState(false);

  const isSelf = Boolean(currentUsername && member.username === currentUsername);
  const isLeader = normalizeGroupRole(member.role) === GROUP_ROLES.LEADER;
  const canKick = canPreviewManageTarget({
    actorUsername: currentUsername,
    actorRole: currentUserRole,
    actorPermissions: currentUserPermissions,
    targetUsername: member.username,
    targetRole: member.role,
    requiredPermission: "KICK_MEMBERS",
  });
  const canBan = canPreviewManageTarget({
    actorUsername: currentUsername,
    actorRole: currentUserRole,
    actorPermissions: currentUserPermissions,
    targetUsername: member.username,
    targetRole: member.role,
    requiredPermission: "BAN_MEMBERS",
  });
  const canManageRoles = canPreviewManageTarget({
    actorUsername: currentUsername,
    actorRole: currentUserRole,
    actorPermissions: currentUserPermissions,
    targetUsername: member.username,
    targetRole: member.role,
    requiredPermission: "MANAGE_ROLES",
  });
  const canTransferLeadership =
    currentUserPermissions.includes("TRANSFER_LEADERSHIP") && !isSelf;
  const displayName = member.fullname?.trim() || member.username;
  const assignableRoles = useMemo(
    () => getAssignableGroupRoles(currentUserRole),
    [currentUserRole],
  );
  const actionCount =
    (canKick ? 1 : 0) +
    (canBan ? 1 : 0) +
    (canManageRoles ? 1 : 0) +
    (canTransferLeadership ? 1 : 0);
  const isBusy = isKickingOut || isBanning || isUpdatingRole || isTransferring;

  const openRoleDialog = () => {
    const currentRole = normalizeGroupRole(member.role);
    const initialRole = assignableRoles.includes(currentRole)
      ? currentRole
      : (assignableRoles[0] || GROUP_ROLES.MEMBER);
    setSelectedRole(initialRole);
    setRoleDialogOpen(true);
  };

  const handleConfirmKickOut = async () => {
    setIsKickingOut(true);
    try {
      await kickGroupMember(groupId, member.userId);
      setKickConfirmOpen(false);
      onMemberKickedOut?.(member.userId);
    } catch (kickOutError: unknown) {
      console.error("Error kicking out group member:", kickOutError);
      onError?.(kickOutError instanceof Error ? kickOutError.message : "Failed to kick out group member");
    } finally {
      setIsKickingOut(false);
    }
  };

  const handleConfirmBan = async () => {
    setIsBanning(true);
    try {
      await banGroupMember(groupId, member.userId, banReason);
      setBanConfirmOpen(false);
      setBanReason("");
      onMemberBanned?.(member.userId);
    } catch (banError: unknown) {
      console.error("Error banning group member:", banError);
      onError?.(banError instanceof Error ? banError.message : "Failed to ban group member");
    } finally {
      setIsBanning(false);
    }
  };

  const handleConfirmRoleUpdate = async () => {
    if (selectedRole === normalizeGroupRole(member.role)) {
      setRoleDialogOpen(false);
      return;
    }

    setIsUpdatingRole(true);
    try {
      const updatedMember = await updateGroupMemberRole(groupId, member.userId, selectedRole);
      setRoleDialogOpen(false);
      onMemberRoleUpdated?.(updatedMember);
    } catch (roleError: unknown) {
      console.error("Error updating member role:", roleError);
      onError?.(roleError instanceof Error ? roleError.message : "Failed to update member role");
    } finally {
      setIsUpdatingRole(false);
    }
  };

  const handleConfirmTransfer = async () => {
    setIsTransferring(true);
    try {
      await transferGroupLeadership(groupId, member.userId);
      setTransferConfirmOpen(false);
      onLeadershipTransferred?.(member.userId);
    } catch (transferError: unknown) {
      console.error("Error transferring leadership:", transferError);
      onError?.(
        transferError instanceof Error ? transferError.message : "Failed to transfer leadership",
      );
    } finally {
      setIsTransferring(false);
    }
  };

  return (
    <div className="group-member-list-item-wrapper">
      <ListItem
        alignItems="flex-start"
        className="group-details-member-item"
        sx={{
          px: 1.5,
          py: 1.25,
          pr: actionCount > 0 ? 10 + actionCount * 4 : 12,
        }}
        secondaryAction={
          <Box sx={{ display: "flex", alignItems: "center", gap: 0.5 }}>
            <Chip
              size="small"
              label={formatGroupRoleLabel(member.role)}
              color={isLeader ? "secondary" : "default"}
              className="group-details-chip"
            />
            {canManageRoles ? (
              <IconButton
                edge="end"
                title={`Change role for ${displayName}`}
                aria-label={`Change role for ${displayName}`}
                onClick={openRoleDialog}
                size="small"
                disabled={isBusy || assignableRoles.length === 0}
              >
                <ManageAccountsOutlinedIcon fontSize="small" />
              </IconButton>
            ) : null}
            {canTransferLeadership ? (
              <IconButton
                edge="end"
                title={`Transfer leadership to ${displayName}`}
                aria-label={`Transfer leadership to ${displayName}`}
                onClick={() => setTransferConfirmOpen(true)}
                size="small"
                disabled={isBusy}
              >
                <SwapHorizOutlinedIcon fontSize="small" />
              </IconButton>
            ) : null}
            {canBan ? (
              <IconButton
                edge="end"
                title={`Ban ${displayName}`}
                aria-label={`Ban ${displayName}`}
                onClick={() => setBanConfirmOpen(true)}
                size="small"
                disabled={isBusy}
              >
                <BlockOutlinedIcon fontSize="small" />
              </IconButton>
            ) : null}
            {canKick ? (
              <IconButton
                edge="end"
                title={`Kick out ${displayName}`}
                aria-label={`Kick out ${displayName}`}
                onClick={() => setKickConfirmOpen(true)}
                size="small"
                disabled={isBusy}
              >
                <KickOutOutlinedIcon fontSize="small" />
              </IconButton>
            ) : null}
          </Box>
        }
      >
        <ListItemText
          primary={
            <Box sx={{ display: "flex", flexWrap: "wrap", gap: 1, alignItems: "center" }}>
              <Typography variant="body2" sx={{ fontWeight: 600 }}>
                {displayName}
              </Typography>
              {isSelf ? (
                <Chip size="small" label="You" variant="outlined" className="group-details-chip" />
              ) : null}
              {isLeader ? (
                <Chip
                  size="small"
                  label="Protected"
                  color="warning"
                  variant="outlined"
                  className="group-details-chip"
                />
              ) : null}
            </Box>
          }
          secondary={
            <Box>
              <Typography variant="body2" className="group-details-muted-text">
                @{member.username}
              </Typography>
              <Typography variant="caption" className="group-details-muted-text">
                Joined {formatAbsoluteTimeVi(member.joinedAt)}
              </Typography>
            </Box>
          }
        />
      </ListItem>

      <Dialog open={kickConfirmOpen} onClose={isKickingOut ? undefined : () => setKickConfirmOpen(false)}>
        <DialogTitle>Kick out member?</DialogTitle>
        <DialogContent>
          <DialogContentText>
            Kick out {displayName} (@{member.username}) from this group? They can rejoin later if invited
            or given a valid join link.
          </DialogContentText>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setKickConfirmOpen(false)} disabled={isKickingOut}>
            Cancel
          </Button>
          <Button onClick={handleConfirmKickOut} color="error" variant="contained" disabled={isKickingOut}>
            {isKickingOut ? "Kicking out..." : "Kick out"}
          </Button>
        </DialogActions>
      </Dialog>

      <Dialog
        open={banConfirmOpen}
        onClose={isBanning ? undefined : () => setBanConfirmOpen(false)}
        maxWidth="sm"
        fullWidth
      >
        <DialogTitle>Ban member?</DialogTitle>
        <DialogContent>
          <DialogContentText sx={{ mb: 2 }}>
            Ban {displayName} (@{member.username}) from this group? They will be removed and cannot rejoin
            until unbanned.
          </DialogContentText>
          <TextField
            fullWidth
            size="small"
            label="Reason (optional)"
            value={banReason}
            onChange={(event) => setBanReason(event.target.value)}
            disabled={isBanning}
            slotProps={{ htmlInput: { maxLength: 500 } }}
            sx={groupDetailsTextFieldSx}
          />
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setBanConfirmOpen(false)} disabled={isBanning}>
            Cancel
          </Button>
          <Button onClick={handleConfirmBan} color="error" variant="contained" disabled={isBanning}>
            {isBanning ? "Banning..." : "Ban"}
          </Button>
        </DialogActions>
      </Dialog>

      <Dialog
        open={roleDialogOpen}
        onClose={isUpdatingRole ? undefined : () => setRoleDialogOpen(false)}
        maxWidth="xs"
        fullWidth
      >
        <DialogTitle>Change role</DialogTitle>
        <DialogContent>
          <DialogContentText sx={{ mb: 2 }}>
            Update the role for {displayName} (@{member.username}). Leadership cannot be assigned here —
            use Transfer leadership instead.
          </DialogContentText>
          <FormControl fullWidth size="small" sx={groupDetailsTextFieldSx}>
            <InputLabel id={`member-role-label-${member.userId}`}>Role</InputLabel>
            <Select
              labelId={`member-role-label-${member.userId}`}
              label="Role"
              value={selectedRole}
              onChange={(event) => setSelectedRole(event.target.value as GroupRole)}
              disabled={isUpdatingRole}
            >
              {assignableRoles.map((role) => (
                <MenuItem key={role} value={role}>
                  {formatGroupRoleLabel(role)}
                </MenuItem>
              ))}
            </Select>
          </FormControl>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setRoleDialogOpen(false)} disabled={isUpdatingRole}>
            Cancel
          </Button>
          <Button
            onClick={handleConfirmRoleUpdate}
            variant="contained"
            disabled={isUpdatingRole || selectedRole === normalizeGroupRole(member.role)}
          >
            {isUpdatingRole ? "Saving..." : "Save role"}
          </Button>
        </DialogActions>
      </Dialog>

      <Dialog
        open={transferConfirmOpen}
        onClose={isTransferring ? undefined : () => setTransferConfirmOpen(false)}
      >
        <DialogTitle>Transfer leadership?</DialogTitle>
        <DialogContent>
          <DialogContentText>
            Make {displayName} (@{member.username}) the new group leader? You will become a member and
            lose leadership permissions.
          </DialogContentText>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setTransferConfirmOpen(false)} disabled={isTransferring}>
            Cancel
          </Button>
          <Button
            onClick={handleConfirmTransfer}
            color="warning"
            variant="contained"
            disabled={isTransferring}
          >
            {isTransferring ? "Transferring..." : "Transfer"}
          </Button>
        </DialogActions>
      </Dialog>
    </div>
  );
}

export default GroupMemberListItem;
