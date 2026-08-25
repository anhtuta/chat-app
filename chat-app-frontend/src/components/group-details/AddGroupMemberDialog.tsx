import React, { useEffect, useRef, useState } from "react";
import {
  Alert,
  Box,
  Button,
  Checkbox,
  CircularProgress,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  FormControlLabel,
  FormGroup,
  TextField,
  Typography,
} from "@mui/material";
import { addGroupMembers, getAddableGroupUsers } from "../../services/api";
import type { SelectableUser } from "../../types/groups";
import { GROUP_MEMBER_LIMIT_REACHED_MESSAGE } from "../../utils/groupMemberLimit";
import "../CreateGroupModal.css";
import { groupDetailsTextFieldSx } from "./groupDetailsFieldSx";

const SEARCH_DEBOUNCE_MS = 400;
const ADDABLE_USERS_CAP = 500;

interface AddGroupMemberDialogProps {
  open: boolean;
  groupId: number | string;
  remainingSeats?: number | null;
  onClose: () => void;
  onMembersAdded: () => void;
}

function AddGroupMemberDialog({
  open,
  groupId,
  remainingSeats = null,
  onClose,
  onMembersAdded,
}: AddGroupMemberDialogProps) {
  const [searchInput, setSearchInput] = useState("");
  const [debouncedSearch, setDebouncedSearch] = useState("");
  const [users, setUsers] = useState<SelectableUser[]>([]);
  const [selectedUserIds, setSelectedUserIds] = useState<number[]>([]);
  const [isLoadingUsers, setIsLoadingUsers] = useState(false);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [error, setError] = useState("");
  const requestIdRef = useRef(0);

  useEffect(() => {
    if (!open) {
      setSearchInput("");
      setDebouncedSearch("");
      setUsers([]);
      setSelectedUserIds([]);
      setError("");
      return;
    }

    const timeoutId = window.setTimeout(() => {
      setDebouncedSearch(searchInput.trim());
    }, SEARCH_DEBOUNCE_MS);
    return () => window.clearTimeout(timeoutId);
  }, [open, searchInput]);

  useEffect(() => {
    if (!open) {
      return;
    }

    let isCancelled = false;
    const requestId = ++requestIdRef.current;
    setIsLoadingUsers(true);
    setError("");

    getAddableGroupUsers(groupId, { q: debouncedSearch })
      .then((nextUsers) => {
        if (isCancelled || requestId !== requestIdRef.current) {
          return;
        }
        setUsers(nextUsers || []);
      })
      .catch((nextError: unknown) => {
        if (isCancelled || requestId !== requestIdRef.current) {
          return;
        }
        console.error("Error loading addable users:", nextError);
        setError(toErrorMessage(nextError, "Failed to load users"));
      })
      .finally(() => {
        if (!isCancelled && requestId === requestIdRef.current) {
          setIsLoadingUsers(false);
        }
      });

    return () => {
      isCancelled = true;
    };
  }, [debouncedSearch, groupId, open]);

  const toggleUser = (userId: number) => {
    setSelectedUserIds((previous) => {
      if (previous.includes(userId)) {
        return previous.filter((id) => id !== userId);
      }
      if (remainingSeats !== null && previous.length >= remainingSeats) {
        return previous;
      }
      return [...previous, userId];
    });
  };

  const selectionAtSeatCap = remainingSeats !== null && selectedUserIds.length >= remainingSeats;

  const handleSubmit = async () => {
    if (!selectedUserIds.length) {
      setError("Select at least one user to add");
      return;
    }
    if (remainingSeats !== null && selectedUserIds.length > remainingSeats) {
      setError(GROUP_MEMBER_LIMIT_REACHED_MESSAGE);
      return;
    }

    setIsSubmitting(true);
    setError("");

    try {
      await addGroupMembers(groupId, selectedUserIds);
      onMembersAdded();
      onClose();
    } catch (submitError: unknown) {
      console.error("Error adding group members:", submitError);
      setError(toErrorMessage(submitError, "Failed to add group members"));
    } finally {
      setIsSubmitting(false);
    }
  };

  return (
    <div className="add-group-member-dialog-wrapper">
      <Dialog open={open} onClose={isSubmitting ? undefined : onClose} maxWidth="sm" fullWidth>
        <DialogTitle>Add members</DialogTitle>
        <DialogContent dividers>
          {error ? (
            <Alert severity="error" sx={{ mb: 2 }}>
              {error}
            </Alert>
          ) : null}

          {remainingSeats !== null ? (
            <Alert severity={remainingSeats === 0 ? "warning" : "info"} sx={{ mb: 2 }}>
              {remainingSeats === 0
                ? GROUP_MEMBER_LIMIT_REACHED_MESSAGE
                : `${remainingSeats} seat${remainingSeats === 1 ? "" : "s"} left. The server still enforces the limit.`}
            </Alert>
          ) : null}

          <TextField
            fullWidth
            size="small"
            label="Search users"
            placeholder="Username or full name"
            value={searchInput}
            onChange={(event) => setSearchInput(event.target.value)}
            disabled={isSubmitting}
            sx={{ ...groupDetailsTextFieldSx, mb: 2 }}
          />

          {isLoadingUsers ? (
            <Box sx={{ display: "flex", justifyContent: "center", py: 4 }}>
              <CircularProgress size={24} />
            </Box>
          ) : users.length ? (
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
                          disabled={remainingSeats === 0 || (selectionAtSeatCap && !selectedUserIds.includes(user.id))}
                          onChange={() => toggleUser(user.id)}
                          onClick={(event) => event.stopPropagation()}
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
              {users.length >= ADDABLE_USERS_CAP ? (
                <Typography variant="caption" color="text.secondary" sx={{ display: "block", mt: 1 }}>
                  Showing first {ADDABLE_USERS_CAP} matches. Refine your search to narrow results.
                </Typography>
              ) : null}
            </Box>
          ) : (
            <Typography variant="body2" color="text.secondary">
              {debouncedSearch
                ? "No users match your search."
                : "No other users are available to add."}
            </Typography>
          )}
        </DialogContent>
        <DialogActions>
          <Button onClick={onClose} disabled={isSubmitting}>
            Cancel
          </Button>
          <Button
            onClick={handleSubmit}
            variant="contained"
            disabled={isLoadingUsers || isSubmitting || selectedUserIds.length === 0 || remainingSeats === 0}
          >
            {isSubmitting ? "Adding..." : "Add"}
          </Button>
        </DialogActions>
      </Dialog>
    </div>
  );
}

function toErrorMessage(error: unknown, fallbackMessage: string): string {
  return error instanceof Error ? error.message : fallbackMessage;
}

export default AddGroupMemberDialog;
