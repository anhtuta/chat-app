import React from "react";
import { Stack, TextField, Typography } from "@mui/material";
import { groupDetailsTextFieldSx } from "./groupDetailsFieldSx";

interface GroupProfileSectionProps {
  groupName: string;
  description: string;
  canManageGroupDetails: boolean;
  isSaving: boolean;
  onGroupNameChange: (value: string) => void;
  onDescriptionChange: (value: string) => void;
}

function GroupProfileSection({
  groupName,
  description,
  canManageGroupDetails,
  isSaving,
  onGroupNameChange,
  onDescriptionChange,
}: GroupProfileSectionProps) {
  return (
    <div className="group-profile-section-wrapper group-details-section">
      <Typography variant="subtitle1" className="group-details-section-title">
        Group profile
      </Typography>
      <Stack spacing={2}>
        <TextField
          label="Group name"
          value={groupName}
          onChange={(event) => onGroupNameChange(event.target.value)}
          fullWidth
          required
          disabled={!canManageGroupDetails || isSaving}
          className="group-details-text-field"
          sx={groupDetailsTextFieldSx}
        />
        <TextField
          label="Description"
          value={description}
          onChange={(event) => onDescriptionChange(event.target.value)}
          fullWidth
          multiline
          minRows={3}
          disabled={!canManageGroupDetails || isSaving}
          helperText={canManageGroupDetails ? "Leave blank to clear the description." : "Read-only"}
          className="group-details-text-field"
          sx={groupDetailsTextFieldSx}
        />
      </Stack>
    </div>
  );
}

export default GroupProfileSection;
