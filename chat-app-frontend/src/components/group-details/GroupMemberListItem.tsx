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
  Typography,
} from "@mui/material";
import KickOutOutlinedIcon from "@mui/icons-material/PersonRemoveOutlined";
import type { GroupMember } from "../../types/groups";
import {
  canPreviewManageTarget,
  formatGroupRoleLabel,
  normalizeGroupRole,
} from "../../utils/groupRoles";
import { formatAbsoluteTimeVi } from "../../utils/dateUtils";
import { kickGroupMember } from "../../services/api";

interface GroupMemberListItemProps {
  groupId: number | string;
  member: GroupMember;
  currentUsername?: string | null;
  currentUserRole?: string | null;
  currentUserPermissions?: string[];
  onMemberKickedOut?: (userId: number) => void;
  onError?: (message: string) => void;
}

function GroupMemberListItem({
  groupId,
  member,
  currentUsername,
  currentUserRole,
  currentUserPermissions = [],
  onMemberKickedOut,
  onError,
}: GroupMemberListItemProps) {
  const [confirmOpen, setConfirmOpen] = useState(false);
  const [isKickingOut, setIsKickingOut] = useState(false);

  const isSelf = Boolean(currentUsername && member.username === currentUsername);
  const isLeader = normalizeGroupRole(member.role) === "LEADER";
  const canKick = canPreviewManageTarget({
    actorUsername: currentUsername,
    actorRole: currentUserRole,
    actorPermissions: currentUserPermissions,
    targetUsername: member.username,
    targetRole: member.role,
    requiredPermission: "KICK_MEMBERS",
  });
  const displayName = member.fullname?.trim() || member.username;

  const handleConfirmKickOut = async () => {
    setIsKickingOut(true);
    try {
      await kickGroupMember(groupId, member.userId);
      setConfirmOpen(false);
      onMemberKickedOut?.(member.userId);
    } catch (kickOutError: unknown) {
      console.error("Error kicking out group member:", kickOutError);
      onError?.(kickOutError instanceof Error ? kickOutError.message : "Failed to kick out group member");
    } finally {
      setIsKickingOut(false);
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
          pr: canKick ? 14 : 12,
        }}
        secondaryAction={
          <Box sx={{ display: "flex", alignItems: "center", gap: 0.5 }}>
            <Chip
              size="small"
              label={formatGroupRoleLabel(member.role)}
              color={isLeader ? "secondary" : "default"}
              className="group-details-chip"
            />
            {canKick ? (
              <IconButton
                edge="end"
                aria-label={`Kick out ${displayName}`}
                onClick={() => setConfirmOpen(true)}
                size="small"
                disabled={isKickingOut}
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

      <Dialog open={confirmOpen} onClose={isKickingOut ? undefined : () => setConfirmOpen(false)}>
        <DialogTitle>Kick out member?</DialogTitle>
        <DialogContent>
          <DialogContentText>
            Kick out {displayName} (@{member.username}) from this group? They can rejoin later if invited
            or given a valid join link.
          </DialogContentText>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setConfirmOpen(false)} disabled={isKickingOut}>
            Cancel
          </Button>
          <Button onClick={handleConfirmKickOut} color="error" variant="contained" disabled={isKickingOut}>
            {isKickingOut ? "Kicking out..." : "Kick out"}
          </Button>
        </DialogActions>
      </Dialog>
    </div>
  );
}

export default GroupMemberListItem;
