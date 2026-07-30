import React from "react";
import {
  Box,
  Chip,
  ListItem,
  ListItemText,
  Typography,
} from "@mui/material";
import type { GroupMember } from "../../types/groups";
import {
  canPreviewManageTarget,
  formatGroupRoleLabel,
  normalizeGroupRole,
  resolveManagePermissionPreview,
} from "../../utils/groupRoles";
import { formatAbsoluteTimeVi } from "../../utils/dateUtils";

interface GroupMemberListItemProps {
  member: GroupMember;
  currentUsername?: string | null;
  currentUserRole?: string | null;
  currentUserPermissions?: string[];
  canSeeModerationControls: boolean;
}

function GroupMemberListItem({
  member,
  currentUsername,
  currentUserRole,
  currentUserPermissions = [],
  canSeeModerationControls,
}: GroupMemberListItemProps) {
  const isSelf = Boolean(currentUsername && member.username === currentUsername);
  const isLeader = normalizeGroupRole(member.role) === "LEADER";
  const manageable = canPreviewManageTarget({
    actorUsername: currentUsername,
    actorRole: currentUserRole,
    actorPermissions: currentUserPermissions,
    targetUsername: member.username,
    targetRole: member.role,
    requiredPermission: resolveManagePermissionPreview(currentUserPermissions),
  });
  const displayName = member.fullname?.trim() || member.username;

  return (
    <div className="group-member-list-item-wrapper">
      <ListItem
        alignItems="flex-start"
        className="group-details-member-item"
        sx={{
          px: 1.5,
          py: 1.25,
          pr: 12,
        }}
        secondaryAction={
          <Chip
            size="small"
            label={formatGroupRoleLabel(member.role)}
            color={isLeader ? "secondary" : "default"}
            className="group-details-chip"
          />
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
              {!isSelf && !isLeader && canSeeModerationControls ? (
                <Chip
                  size="small"
                  label={manageable ? "Manageable" : "Out of reach"}
                  color={manageable ? "success" : "default"}
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
    </div>
  );
}

export default GroupMemberListItem;
