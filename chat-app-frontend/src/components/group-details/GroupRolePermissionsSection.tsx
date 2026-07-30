import React from "react";
import { Box, Chip, Stack, Typography } from "@mui/material";

interface GroupRolePermissionsSectionProps {
  currentUserRole?: string | null;
  permissions: string[];
}

function GroupRolePermissionsSection({
  currentUserRole,
  permissions,
}: GroupRolePermissionsSectionProps) {
  const canSeeSettingsControls = permissions.includes("MANAGE_GROUP_DETAILS");
  const canSeeModerationControls = permissions.some((permission) =>
    ["KICK_MEMBERS", "BAN_MEMBERS", "MANAGE_ROLES", "UNBAN_MEMBERS"].includes(permission),
  );
  const canTransferLeadership = permissions.includes("TRANSFER_LEADERSHIP");

  return (
    <div className="group-role-permissions-section-wrapper group-details-section">
      <Typography variant="subtitle1" className="group-details-section-title">
        Your access
      </Typography>
      <Stack spacing={2}>
        <Box>
          <Typography variant="subtitle2" className="group-details-section-subtitle">
            Your role
          </Typography>
          <Chip
            label={currentUserRole || "MEMBER"}
            size="small"
            className="group-details-chip"
          />
        </Box>

        <Box>
          <Typography variant="subtitle2" className="group-details-section-subtitle">
            Granted permissions
          </Typography>
          {permissions.length ? (
            <Box sx={{ display: "flex", flexWrap: "wrap", gap: 1 }}>
              {permissions.map((permission) => (
                <Chip
                  key={permission}
                  label={permission}
                  size="small"
                  variant="outlined"
                  className="group-details-chip"
                />
              ))}
            </Box>
          ) : (
            <Typography variant="body2" className="group-details-muted-text">
              No explicit permissions available.
            </Typography>
          )}
        </Box>

        <Box>
          <Typography variant="subtitle2" className="group-details-section-subtitle">
            Your visible controls
          </Typography>
          <Box sx={{ display: "flex", flexWrap: "wrap", gap: 1 }}>
            <Chip
              size="small"
              color={canSeeSettingsControls ? "primary" : "default"}
              variant={canSeeSettingsControls ? "filled" : "outlined"}
              label={canSeeSettingsControls ? "Settings editable" : "Settings read-only"}
              className="group-details-chip"
            />
            <Chip
              size="small"
              color={canSeeModerationControls ? "primary" : "default"}
              variant={canSeeModerationControls ? "filled" : "outlined"}
              label={canSeeModerationControls ? "Moderation visible" : "No moderation controls"}
              className="group-details-chip"
            />
            <Chip
              size="small"
              color={canTransferLeadership ? "primary" : "default"}
              variant={canTransferLeadership ? "filled" : "outlined"}
              label={canTransferLeadership ? "Leadership transfer allowed" : "Cannot transfer leadership"}
              className="group-details-chip"
            />
          </Box>
          <Typography variant="caption" className="group-details-muted-text group-details-caption">
            Action buttons land in later phases. This section only shows who would see what.
          </Typography>
        </Box>
      </Stack>
    </div>
  );
}

export default GroupRolePermissionsSection;
