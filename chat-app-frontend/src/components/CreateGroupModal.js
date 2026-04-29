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
        <DialogTitle sx={{ fontWeight: "bold" }}>Create New Group</DialogTitle>
        <DialogContent dividers>
          <form onSubmit={handleSubmit}>
            {error && (
              <Alert severity="error" sx={{ mb: 2 }}>
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
              sx={{ mb: 3, mt: 1 }}
              variant="outlined"
            />

            <Typography variant="subtitle2" sx={{ fontWeight: "bold", mb: 2 }}>
              Select Participants
            </Typography>

            <Box
              sx={{
                maxHeight: 300,
                overflowY: "auto",
                border: "1px solid var(--color-border)",
                borderRadius: 1,
                p: 1,
              }}
            >
              <FormGroup>
                {users.map((user) => (
                  <Box
                    key={user.id}
                    sx={{
                      p: 1.5,
                      borderRadius: 1,
                      backgroundColor: selectedUserIds.includes(user.id)
                        ? "var(--color-selection-bg)"
                        : "transparent",
                      "&:hover": {
                        backgroundColor: "var(--color-surface-soft)",
                      },
                      cursor: "pointer",
                    }}
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
                          <Typography variant="body2" sx={{ fontWeight: 500 }}>
                            {user.fullname || user.username}
                          </Typography>
                          <Typography variant="caption" color="textSecondary">
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
        <DialogActions sx={{ p: 2 }}>
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
                <CircularProgress size={20} sx={{ mr: 1 }} />
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
