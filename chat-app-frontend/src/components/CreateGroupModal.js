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
import "./CreateGroupModal.css";

function CreateGroupModal({ onClose, onGroupCreated }) {
  const [groupName, setGroupName] = useState("");
  const [users, setUsers] = useState([]);
  const [selectedUserIds, setSelectedUserIds] = useState([]);
  const [isLoading, setIsLoading] = useState(false);
  const [error, setError] = useState(null);

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

  const toggleUser = (userId) => {
    setSelectedUserIds((prev) => {
      if (prev.includes(userId)) {
        return prev.filter((id) => id !== userId);
      } else {
        return [...prev, userId];
      }
    });
  };

  const handleSubmit = async (e) => {
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

    setIsLoading(true);
    try {
      const newGroup = await createGroup(groupName.trim(), selectedUserIds);
      onGroupCreated(newGroup);
      onClose();
    } catch (error) {
      console.error("Error creating group:", error);
      setError("Error creating group: " + error.message);
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
