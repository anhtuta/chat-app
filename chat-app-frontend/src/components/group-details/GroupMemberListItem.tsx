import React, { useState } from "react";
import {
  Box,
  Button,
  Chip,
  Dialog,
  DialogActions,
  DialogContent,
  DialogContentText,
  DialogTitle,
  IconButton,
  ListItem,
  ListItemText,
  TextField,
  Typography,
} from "@mui/material";
import BlockOutlinedIcon from "@mui/icons-material/BlockOutlined";
import KickOutOutlinedIcon from "@mui/icons-material/PersonRemoveOutlined";
import type { GroupMember } from "../../types/groups";
import {
  canPreviewManageTarget,
  formatGroupRoleLabel,
  normalizeGroupRole,
} from "../../utils/groupRoles";
import { GROUP_ROLES } from "../../constant/groupRoles";
import { formatAbsoluteTimeVi } from "../../utils/dateUtils";
import { banGroupMember, kickGroupMember } from "../../services/api";
import { groupDetailsTextFieldSx } from "./groupDetailsFieldSx";

interface GroupMemberListItemProps {
  groupId: number | string;
  member: GroupMember;
  currentUsername?: string | null;
  currentUserRole?: string | null;
  currentUserPermissions?: string[];
  onMemberKickedOut?: (userId: number) => void;
  onMemberBanned?: (userId: number) => void;
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
  onError,
}: GroupMemberListItemProps) {
  const [kickConfirmOpen, setKickConfirmOpen] = useState(false);
  const [banConfirmOpen, setBanConfirmOpen] = useState(false);
  const [banReason, setBanReason] = useState("");
  const [isKickingOut, setIsKickingOut] = useState(false);
  const [isBanning, setIsBanning] = useState(false);

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
  const displayName = member.fullname?.trim() || member.username;
  const actionCount = (canKick ? 1 : 0) + (canBan ? 1 : 0);

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
            {canBan ? (
              <IconButton
                edge="end"
                title={`Ban ${displayName}`}
                aria-label={`Ban ${displayName}`}
                onClick={() => setBanConfirmOpen(true)}
                size="small"
                disabled={isBanning || isKickingOut}
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
                disabled={isKickingOut || isBanning}
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
    </div>
  );
}

export default GroupMemberListItem;
