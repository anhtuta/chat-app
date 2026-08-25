import React, { useState, useEffect } from "react";
import {
  Dialog,
  DialogTitle,
  DialogContent,
  DialogActions,
  TextField,
  FormGroup,
  FormControlLabel,
  Checkbox,
  Button,
  Box,
  Typography,
  CircularProgress,
  Alert,
} from "@mui/material";
import { getUsers, createGroup } from "../services/api";
import type { ChatGroup, SelectableUser } from "../types/groups";
import { parseMaxMembersInput, GROUP_MEMBER_LIMIT_REACHED_MESSAGE } from "../utils/groupMemberLimit";
import "./CreateGroupModal.css";

interface CreateGroupModalProps {
  onClose: () => void;
  onGroupCreated: (group: ChatGroup) => void;
}

function CreateGroupModal({ onClose, onGroupCreated }: CreateGroupModalProps) {
  const [groupName, setGroupName] = useState("");
  const [maxMembersInput, setMaxMembersInput] = useState("");
  const [users, setUsers] = useState<SelectableUser[]>([]);
  const [selectedUserIds, setSelectedUserIds] = useState<number[]>([]);
  const [isLoading, setIsLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    loadUsers();
  }, []);

  const loadUsers = async () => {
    try {
      const usersData = await getUsers();
      setUsers(usersData);
    } catch (error) {
      console.error("Error loading users:", error);
      setError("Error loading users");
    }
  };

  const toggleUser = (userId: number) => {
    setSelectedUserIds((prev) => {
      if (prev.includes(userId)) {
        return prev.filter((id) => id !== userId);
      } else {
        return [...prev, userId];
      }
    });
  };

  const handleSubmit = async (e: React.SyntheticEvent) => {
    e.preventDefault();
    setError(null);

    if (!groupName.trim()) {
      setError("Please enter a group name");
      return;
    }

    if (selectedUserIds.length === 0) {
      setError("Please select at least one participant");
      return;
    }

    const parsedMaxMembers = parseMaxMembersInput(maxMembersInput);
    if (parsedMaxMembers.ok === false) {
      setError(parsedMaxMembers.message);
      return;
    }
    if (parsedMaxMembers.value != null && parsedMaxMembers.value > 0) {
      const initialCount = selectedUserIds.length + 1;
      if (initialCount > parsedMaxMembers.value) {
        setError(GROUP_MEMBER_LIMIT_REACHED_MESSAGE);
        return;
      }
    }

    setIsLoading(true);
    try {
      const newGroup = await createGroup(
        groupName.trim(),
        selectedUserIds,
        undefined,
        parsedMaxMembers.value,
      );
      onGroupCreated(newGroup);
      onClose();
    } catch (err) {
      console.error("Error creating group:", err);
      const message = err instanceof Error ? err.message : String(err);
      setError("Error creating group: " + message);
    } finally {
      setIsLoading(false);
    }
  };

  return (
    <div className="create-group-modal-wrapper">
      <Dialog open={true} onClose={onClose} maxWidth="sm" fullWidth>
        <DialogTitle className="create-group-dialog-title">Create New Group</DialogTitle>
        <DialogContent dividers>
          <form onSubmit={handleSubmit}>
            {error && (
              <Alert severity="error" className="create-group-alert">
                {error}
              </Alert>
            )}

            <TextField
              label="Group Name"
              placeholder="Enter group name"
              value={groupName}
              onChange={(e) => setGroupName(e.target.value)}
              fullWidth
              required
              className="create-group-name-field"
              variant="outlined"
            />

            <TextField
              label="Maximum members"
              placeholder="Unlimited"
              value={maxMembersInput}
              onChange={(e) => setMaxMembersInput(e.target.value)}
              fullWidth
              helperText="Leave blank or 0 for unlimited."
              className="create-group-name-field"
              variant="outlined"
            />

            <Typography variant="subtitle2" className="create-group-participants-label">
              Select Participants
            </Typography>

            <Box className="create-group-users-container">
              <FormGroup>
                {users.map((user) => (
                  <Box
                    key={user.id}
                    className={`create-group-user-item ${selectedUserIds.includes(user.id) ? "selected" : ""}`}
                    onClick={() => toggleUser(user.id)}
                  >
                    <FormControlLabel
                      control={
                        <Checkbox
                          checked={selectedUserIds.includes(user.id)}
                          onChange={() => toggleUser(user.id)}
                          onClick={(e) => e.stopPropagation()}
                        />
                      }
                      label={
                        <Box>
                          <Typography variant="body2" className="create-group-user-fullname">
                            {user.fullname || user.username}
                          </Typography>
                          <Typography variant="caption" className="create-group-user-username">
                            @{user.username}
                          </Typography>
                        </Box>
                      }
                    />
                  </Box>
                ))}
              </FormGroup>
            </Box>
          </form>
        </DialogContent>
        <DialogActions className="create-group-dialog-actions">
          <Button onClick={onClose} disabled={isLoading} variant="outlined">
            Cancel
          </Button>
          <Button
            onClick={handleSubmit}
            disabled={isLoading || !groupName.trim() || selectedUserIds.length === 0}
            variant="contained"
          >
            {isLoading ? (
              <>
                <CircularProgress size={20} className="create-group-loading-spinner" />
                Creating...
              </>
            ) : (
              "Create Group"
            )}
          </Button>
        </DialogActions>
      </Dialog>
    </div>
  );
}

export default CreateGroupModal;
