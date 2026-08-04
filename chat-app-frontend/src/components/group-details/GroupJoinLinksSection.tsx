import React, { useEffect, useMemo, useRef, useState } from "react";
import {
  Alert,
  Box,
  Button,
  Chip,
  CircularProgress,
  Dialog,
  DialogActions,
  DialogContent,
  DialogContentText,
  DialogTitle,
  FormControl,
  IconButton,
  InputLabel,
  List,
  ListItem,
  ListItemText,
  MenuItem,
  Select,
  Stack,
  Typography,
} from "@mui/material";
import ContentCopyOutlinedIcon from "@mui/icons-material/ContentCopyOutlined";
import LinkOutlinedIcon from "@mui/icons-material/LinkOutlined";
import BlockOutlinedIcon from "@mui/icons-material/BlockOutlined";
import {
  createGroupJoinLink,
  getGroupJoinLinks,
  revokeGroupJoinLink,
} from "../../services/api";
import type { GroupJoinLink } from "../../types/groups";
import { formatAbsoluteTimeVi } from "../../utils/dateUtils";
import { buildJoinLinkUrl } from "../../utils/joinLinks";
import { groupDetailsTextFieldSx } from "./groupDetailsFieldSx";

type ExpiryOption = "none" | "1d" | "7d" | "30d";
type JoinLinkStatus = "ACTIVE" | "EXPIRED" | "REVOKED";

interface GroupJoinLinksSectionProps {
  groupId: number | string | null | undefined;
  open: boolean;
  canManageJoinLinks: boolean;
}

function GroupJoinLinksSection({
  groupId,
  open,
  canManageJoinLinks,
}: GroupJoinLinksSectionProps) {
  const [links, setLinks] = useState<GroupJoinLink[]>([]);
  const [knownTokensById, setKnownTokensById] = useState<Record<number, string>>({});
  const [expiryOption, setExpiryOption] = useState<ExpiryOption>("7d");
  const [isLoading, setIsLoading] = useState(false);
  const [isCreating, setIsCreating] = useState(false);
  const [isRevoking, setIsRevoking] = useState(false);
  const [error, setError] = useState("");
  const [copyFeedback, setCopyFeedback] = useState("");
  const [pendingRevoke, setPendingRevoke] = useState<GroupJoinLink | null>(null);
  const [newlyCreatedLink, setNewlyCreatedLink] = useState<GroupJoinLink | null>(null);
  const requestIdRef = useRef(0);

  useEffect(() => {
    if (!open || !canManageJoinLinks || groupId === null || groupId === undefined) {
      return;
    }

    let isCancelled = false;
    const requestId = ++requestIdRef.current;
    setIsLoading(true);
    setError("");

    getGroupJoinLinks(groupId)
      .then((nextLinks) => {
        if (isCancelled || requestId !== requestIdRef.current) {
          return;
        }
        setLinks(nextLinks || []);
      })
      .catch((nextError: unknown) => {
        if (isCancelled || requestId !== requestIdRef.current) {
          return;
        }
        console.error("Error loading join links:", nextError);
        setError(toErrorMessage(nextError, "Failed to load join links"));
      })
      .finally(() => {
        if (!isCancelled && requestId === requestIdRef.current) {
          setIsLoading(false);
        }
      });

    return () => {
      isCancelled = true;
    };
  }, [canManageJoinLinks, groupId, open]);

  useEffect(() => {
    if (!open) {
      setExpiryOption("7d");
      setError("");
      setCopyFeedback("");
      setPendingRevoke(null);
      setNewlyCreatedLink(null);
      setKnownTokensById({});
    }
  }, [open]);

  const displayLinks = useMemo(
    () =>
      links.map((link) => ({
        ...link,
        token: link.token || knownTokensById[link.id] || null,
      })),
    [knownTokensById, links],
  );

  if (!canManageJoinLinks || groupId === null || groupId === undefined) {
    return null;
  }

  const handleCreate = async () => {
    setIsCreating(true);
    setError("");
    setCopyFeedback("");
    try {
      const created = await createGroupJoinLink(groupId, toExpiresAtIso(expiryOption));
      setLinks((previous) => [created, ...previous.filter((link) => link.id !== created.id)]);
      if (created.token) {
        setKnownTokensById((previous) => ({ ...previous, [created.id]: created.token as string }));
      }
      setNewlyCreatedLink(created);
    } catch (createError: unknown) {
      console.error("Error creating join link:", createError);
      setError(toErrorMessage(createError, "Failed to create join link"));
    } finally {
      setIsCreating(false);
    }
  };

  const handleCopyToken = async (token: string) => {
    try {
      await navigator.clipboard.writeText(buildJoinLinkUrl(token));
      setCopyFeedback("Join link copied");
    } catch (copyError: unknown) {
      console.error("Error copying join link:", copyError);
      setError("Failed to copy join link");
    }
  };

  const handleConfirmRevoke = async () => {
    if (!pendingRevoke) {
      return;
    }

    setIsRevoking(true);
    setError("");
    try {
      await revokeGroupJoinLink(groupId, pendingRevoke.id);
      const revokedAt = new Date().toISOString();
      setLinks((previous) =>
        previous.map((link) =>
          link.id === pendingRevoke.id ? { ...link, revokedAt } : link,
        ),
      );
      if (newlyCreatedLink?.id === pendingRevoke.id) {
        setNewlyCreatedLink((previous) => (previous ? { ...previous, revokedAt } : previous));
      }
      setPendingRevoke(null);
    } catch (revokeError: unknown) {
      console.error("Error revoking join link:", revokeError);
      setError(toErrorMessage(revokeError, "Failed to revoke join link"));
    } finally {
      setIsRevoking(false);
    }
  };

  return (
    <div className="group-join-links-section-wrapper group-details-section">
      <Typography variant="subtitle1" className="group-details-section-title">
        Join links
      </Typography>
      <Typography variant="body2" className="group-details-muted-text" sx={{ mb: 1.5 }}>
        Create a shareable join link others can open. The raw token is shown only when you create a
        link; copy the full URL before closing this dialog.
      </Typography>

      {error ? (
        <Alert severity="error" sx={{ mb: 1.5 }}>
          {error}
        </Alert>
      ) : null}
      {copyFeedback ? (
        <Alert severity="success" sx={{ mb: 1.5 }} onClose={() => setCopyFeedback("")}>
          {copyFeedback}
        </Alert>
      ) : null}

      <Stack direction={{ xs: "column", sm: "row" }} spacing={1} sx={{ mb: 2, alignItems: "stretch" }}>
        <FormControl size="small" sx={{ ...groupDetailsTextFieldSx, minWidth: 160, flex: 1 }}>
          <InputLabel id="join-link-expiry-label">Expiry</InputLabel>
          <Select
            labelId="join-link-expiry-label"
            label="Expiry"
            value={expiryOption}
            onChange={(event) => setExpiryOption(event.target.value as ExpiryOption)}
            disabled={isCreating}
          >
            <MenuItem value="none">No expiry</MenuItem>
            <MenuItem value="1d">1 day</MenuItem>
            <MenuItem value="7d">7 days</MenuItem>
            <MenuItem value="30d">30 days</MenuItem>
          </Select>
        </FormControl>
        <Button
          variant="contained"
          startIcon={<LinkOutlinedIcon />}
          onClick={() => {
            void handleCreate();
          }}
          disabled={isCreating || isLoading}
          title="Create join link"
        >
          {isCreating ? "Creating..." : "Create link"}
        </Button>
      </Stack>

      {newlyCreatedLink?.token ? (
        <Alert severity="info" sx={{ mb: 2 }}>
          <Typography variant="body2" sx={{ mb: 1, fontWeight: 600 }}>
            New join link (copy now)
          </Typography>
          <Box sx={{ display: "flex", alignItems: "center", gap: 1, flexWrap: "wrap" }}>
            <Typography
              variant="body2"
              sx={{ fontFamily: "monospace", wordBreak: "break-all", flex: 1 }}
            >
              {buildJoinLinkUrl(newlyCreatedLink.token)}
            </Typography>
            <IconButton
              size="small"
              title="Copy join link"
              aria-label="Copy join link"
              onClick={() => {
                void handleCopyToken(newlyCreatedLink.token as string);
              }}
            >
              <ContentCopyOutlinedIcon fontSize="small" />
            </IconButton>
          </Box>
        </Alert>
      ) : null}

      {isLoading ? (
        <Box sx={{ display: "flex", justifyContent: "center", py: 3 }}>
          <CircularProgress size={24} />
        </Box>
      ) : (
        <Box className="group-details-member-scroll">
          <List dense disablePadding>
            {displayLinks.map((link) => {
              const status = resolveJoinLinkStatus(link);
              const canRevoke = status === "ACTIVE";
              const canCopy = Boolean(link.token);
              return (
                <ListItem
                  key={link.id}
                  alignItems="flex-start"
                  className="group-details-member-item"
                  sx={{ px: 1.5, py: 1.25, pr: 10 }}
                  secondaryAction={
                    <Box sx={{ display: "flex", alignItems: "center", gap: 0.25 }}>
                      {canCopy ? (
                        <IconButton
                          edge="end"
                          size="small"
                          title="Copy join link"
                          aria-label="Copy join link"
                          onClick={() => {
                            void handleCopyToken(link.token as string);
                          }}
                        >
                          <ContentCopyOutlinedIcon fontSize="small" />
                        </IconButton>
                      ) : null}
                      {canRevoke ? (
                        <IconButton
                          edge="end"
                          size="small"
                          title="Revoke join link"
                          aria-label="Revoke join link"
                          onClick={() => setPendingRevoke(link)}
                          disabled={isRevoking}
                        >
                          <BlockOutlinedIcon fontSize="small" />
                        </IconButton>
                      ) : null}
                    </Box>
                  }
                >
                  <ListItemText
                    primary={
                      <Box sx={{ display: "flex", flexWrap: "wrap", gap: 1, alignItems: "center" }}>
                        <Typography variant="body2" sx={{ fontWeight: 600 }}>
                          Link #{link.id}
                        </Typography>
                        <Chip
                          size="small"
                          label={status}
                          color={statusChipColor(status)}
                          variant="outlined"
                          className="group-details-chip"
                        />
                      </Box>
                    }
                    secondary={
                      <Box>
                        <Typography variant="caption" className="group-details-muted-text" sx={{ display: "block" }}>
                          Created {formatAbsoluteTimeVi(link.createdAt)}
                          {link.createdByUsername ? ` by @${link.createdByUsername}` : ""}
                        </Typography>
                        <Typography variant="caption" className="group-details-muted-text" sx={{ display: "block" }}>
                          {link.expiresAt
                            ? `Expires ${formatAbsoluteTimeVi(link.expiresAt)}`
                            : "Does not expire"}
                        </Typography>
                        {!link.token ? (
                          <Typography variant="caption" className="group-details-muted-text" sx={{ display: "block" }}>
                            Token unavailable after creation
                          </Typography>
                        ) : null}
                      </Box>
                    }
                  />
                </ListItem>
              );
            })}
            {!displayLinks.length ? (
              <Typography variant="body2" className="group-details-muted-text" sx={{ px: 1.5, py: 2 }}>
                No join links yet.
              </Typography>
            ) : null}
          </List>
        </Box>
      )}

      <Dialog open={Boolean(pendingRevoke)} onClose={isRevoking ? undefined : () => setPendingRevoke(null)}>
        <DialogTitle>Revoke join link?</DialogTitle>
        <DialogContent>
          <DialogContentText>
            Revoke link #{pendingRevoke?.id}? People with this token will no longer be able to join.
          </DialogContentText>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setPendingRevoke(null)} disabled={isRevoking}>
            Cancel
          </Button>
          <Button
            onClick={handleConfirmRevoke}
            color="error"
            variant="contained"
            disabled={isRevoking}
          >
            {isRevoking ? "Revoking..." : "Revoke"}
          </Button>
        </DialogActions>
      </Dialog>
    </div>
  );
}

function toExpiresAtIso(option: ExpiryOption): string | null {
  if (option === "none") {
    return null;
  }
  const days = option === "1d" ? 1 : option === "7d" ? 7 : 30;
  // Absolute UTC instant so browser TZ and server Instant.now() agree on ACTIVE/EXPIRED.
  return new Date(Date.now() + days * 24 * 60 * 60 * 1000).toISOString();
}

function resolveJoinLinkStatus(link: GroupJoinLink): JoinLinkStatus {
  if (link.revokedAt) {
    return "REVOKED";
  }
  if (link.expiresAt && new Date(link.expiresAt).getTime() <= Date.now()) {
    return "EXPIRED";
  }
  return "ACTIVE";
}

function statusChipColor(status: JoinLinkStatus): "success" | "warning" | "default" {
  if (status === "ACTIVE") {
    return "success";
  }
  if (status === "EXPIRED") {
    return "warning";
  }
  return "default";
}

function toErrorMessage(error: unknown, fallbackMessage: string): string {
  return error instanceof Error ? error.message : fallbackMessage;
}

export default GroupJoinLinksSection;
