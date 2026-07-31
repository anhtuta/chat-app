import React, { useState } from "react";
import {
  Alert,
  Box,
  Button,
  CircularProgress,
  Dialog,
  DialogActions,
  DialogContent,
  DialogContentText,
  DialogTitle,
  Typography,
} from "@mui/material";
import ExitToAppOutlinedIcon from "@mui/icons-material/ExitToAppOutlined";
import { getGroupMembers, leaveGroup } from "../../services/api";
import { GROUP_ROLES } from "../../constant/groupRoles";
import { normalizeGroupRole } from "../../utils/groupRoles";

interface LeaveGroupSectionProps {
  groupId: number | string;
  groupName?: string | null;
  currentUserRole?: string | null;
  disabled?: boolean;
  onLeft: () => void;
}

function LeaveGroupSection({
  groupId,
  groupName,
  currentUserRole,
  disabled = false,
  onLeft,
}: LeaveGroupSectionProps) {
  const [confirmOpen, setConfirmOpen] = useState(false);
  const [isCheckingConstraints, setIsCheckingConstraints] = useState(false);
  const [isLeaving, setIsLeaving] = useState(false);
  const [memberCount, setMemberCount] = useState<number | null>(null);
  const [error, setError] = useState("");

  const isLeader = normalizeGroupRole(currentUserRole) === GROUP_ROLES.LEADER;
  const mustTransferFirst = isLeader && memberCount !== null && memberCount > 1;
  const isLastMember = memberCount !== null && memberCount <= 1;
  const canConfirmLeave = !isCheckingConstraints && !mustTransferFirst && (!isLeader || memberCount !== null);
  const displayGroupName = groupName?.trim() || "this group";

  const openConfirm = async () => {
    setConfirmOpen(true);
    setError("");
    setMemberCount(null);

    if (!isLeader) {
      return;
    }

    setIsCheckingConstraints(true);
    try {
      const page = await getGroupMembers(groupId, { page: 0, size: 1 });
      setMemberCount(page.totalElements ?? 0);
    } catch (checkError: unknown) {
      console.error("Error checking leave constraints:", checkError);
      setError(toErrorMessage(checkError, "Failed to check whether you can leave"));
    } finally {
      setIsCheckingConstraints(false);
    }
  };

  const closeConfirm = () => {
    if (isLeaving || isCheckingConstraints) {
      return;
    }
    setConfirmOpen(false);
    setError("");
    setMemberCount(null);
  };

  const handleConfirmLeave = async () => {
    if (mustTransferFirst) {
      return;
    }

    setIsLeaving(true);
    setError("");
    try {
      await leaveGroup(groupId);
      setConfirmOpen(false);
      onLeft();
    } catch (leaveError: unknown) {
      console.error("Error leaving group:", leaveError);
      setError(toErrorMessage(leaveError, "Failed to leave group"));
    } finally {
      setIsLeaving(false);
    }
  };

  return (
    <div className="leave-group-section-wrapper group-details-section">
      <Typography variant="subtitle1" className="group-details-section-title">
        Leave group
      </Typography>
      <Typography variant="body2" className="group-details-muted-text" sx={{ mb: 1.5 }}>
        You will lose access to this chat until you are added again
        {isLeader ? ". Leaders must transfer leadership first unless they are the last member." : "."}
      </Typography>
      <Button
        color="error"
        variant="outlined"
        startIcon={<ExitToAppOutlinedIcon />}
        onClick={() => {
          void openConfirm();
        }}
        disabled={disabled || isLeaving}
        title="Leave group"
      >
        Leave group
      </Button>

      <Dialog open={confirmOpen} onClose={closeConfirm} maxWidth="sm" fullWidth>
        <DialogTitle>Leave group?</DialogTitle>
        <DialogContent>
          {error ? (
            <Alert severity="error" sx={{ mb: 2 }}>
              {error}
            </Alert>
          ) : null}

          {isCheckingConstraints ? (
            <Box sx={{ display: "flex", justifyContent: "center", py: 3 }}>
              <CircularProgress size={24} />
            </Box>
          ) : mustTransferFirst ? (
            <DialogContentText>
              You are the leader of {displayGroupName} and other members are still here. Transfer
              leadership to someone else before leaving.
            </DialogContentText>
          ) : (
            <DialogContentText>
              {isLastMember
                ? `You are the last member of ${displayGroupName}. Leaving will archive the group.`
                : `Leave ${displayGroupName}? You can rejoin later only if invited or given a valid join link.`}
            </DialogContentText>
          )}
        </DialogContent>
        <DialogActions>
          <Button onClick={closeConfirm} disabled={isLeaving || isCheckingConstraints}>
            Cancel
          </Button>
          {!mustTransferFirst && canConfirmLeave ? (
            <Button
              onClick={handleConfirmLeave}
              color="error"
              variant="contained"
              disabled={isLeaving}
            >
              {isLeaving ? "Leaving..." : isLastMember ? "Leave and archive" : "Leave"}
            </Button>
          ) : null}
        </DialogActions>
      </Dialog>
    </div>
  );
}

function toErrorMessage(error: unknown, fallbackMessage: string): string {
  return error instanceof Error ? error.message : fallbackMessage;
}

export default LeaveGroupSection;
