import React from "react";
import { Stack, TextField, Typography } from "@mui/material";
import { isAllowedMaxMembersInput } from "../../utils/groupMemberLimit";
import { groupDetailsTextFieldSx } from "./groupDetailsFieldSx";

interface GroupProfileSectionProps {
  groupName: string;
  description: string;
  maxMembersInput: string;
  canManageGroupDetails: boolean;
  isSaving: boolean;
  onGroupNameChange: (value: string) => void;
  onDescriptionChange: (value: string) => void;
  onMaxMembersChange: (value: string) => void;
}

function GroupProfileSection({
  groupName,
  description,
  maxMembersInput,
  canManageGroupDetails,
  isSaving,
  onGroupNameChange,
  onDescriptionChange,
  onMaxMembersChange,
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
        <TextField
          label="Maximum members"
          type="number"
          value={maxMembersInput}
          onChange={(event) => {
            const next = event.target.value;
            if (isAllowedMaxMembersInput(next)) {
              onMaxMembersChange(next);
            }
          }}
          onKeyDown={(event) => {
            if (["e", "E", "+", "-", "."].includes(event.key)) {
              event.preventDefault();
            }
          }}
          slotProps={{ htmlInput: { min: 0, step: 1, inputMode: "numeric" } }}
          fullWidth
          placeholder="Unlimited"
          disabled={!canManageGroupDetails || isSaving}
          helperText={
            canManageGroupDetails
              ? "Leave blank or 0 for unlimited. Lowering below the current roster is allowed; new joins stay blocked until there is a free seat."
              : maxMembersInput.trim()
                ? "Read-only"
                : "Unlimited"
          }
          className="group-details-text-field"
          sx={groupDetailsTextFieldSx}
        />
      </Stack>
    </div>
  );
}

export default GroupProfileSection;
