import React, { useEffect, useRef, useState } from "react";
import {
  Alert,
  Box,
  Button,
  CircularProgress,
  List,
  Stack,
  TextField,
  Typography,
} from "@mui/material";
import PersonAddAlt1OutlinedIcon from "@mui/icons-material/PersonAddAlt1Outlined";
import { getGroupMembers } from "../../services/api";
import type { GroupMember } from "../../types/groups";
import { GROUP_ROLES } from "../../constant/groupRoles";
import { groupDetailsTextFieldSx } from "./groupDetailsFieldSx";
import GroupMemberListItem from "./GroupMemberListItem";
import AddGroupMemberDialog from "./AddGroupMemberDialog";

const MEMBER_PAGE_SIZE = 100;
const SEARCH_DEBOUNCE_MS = 400;
const SCROLL_LOAD_THRESHOLD_PX = 48;

interface GroupMemberListProps {
  groupId: number | string | null | undefined;
  open: boolean;
  currentUsername?: string | null;
  currentUserRole?: string | null;
  currentUserPermissions?: string[];
  onMemberBanned?: () => void;
  onLeadershipTransferred?: () => void;
}

function GroupMemberList({
  groupId,
  open,
  currentUsername,
  currentUserRole,
  currentUserPermissions = [],
  onMemberBanned,
  onLeadershipTransferred,
}: GroupMemberListProps) {
  const [searchInput, setSearchInput] = useState("");
  const [debouncedSearch, setDebouncedSearch] = useState("");
  const [members, setMembers] = useState<GroupMember[]>([]);
  const [page, setPage] = useState(0);
  const [hasNext, setHasNext] = useState(false);
  const [totalElements, setTotalElements] = useState(0);
  const [isLoading, setIsLoading] = useState(false);
  const [isLoadingMore, setIsLoadingMore] = useState(false);
  const [error, setError] = useState("");
  const [addDialogOpen, setAddDialogOpen] = useState(false);
  const [reloadToken, setReloadToken] = useState(0);
  const requestIdRef = useRef(0);

  const canAddMembers = currentUserPermissions.includes("ADD_MEMBERS");

  useEffect(() => {
    const timeoutId = window.setTimeout(() => {
      setDebouncedSearch(searchInput.trim());
    }, SEARCH_DEBOUNCE_MS);
    return () => window.clearTimeout(timeoutId);
  }, [searchInput]);

  useEffect(() => {
    if (!open || groupId === null || groupId === undefined) {
      return;
    }

    let isCancelled = false;
    const requestId = ++requestIdRef.current;
    setIsLoading(true);
    setIsLoadingMore(false);
    setError("");
    setMembers([]);
    setPage(0);
    setHasNext(false);
    setTotalElements(0);

    getGroupMembers(groupId, { q: debouncedSearch, page: 0, size: MEMBER_PAGE_SIZE })
      .then((response) => {
        if (isCancelled || requestId !== requestIdRef.current) {
          return;
        }
        setMembers(response.content || []);
        setPage(response.page ?? 0);
        setHasNext(Boolean(response.hasNext));
        setTotalElements(response.totalElements ?? 0);
      })
      .catch((nextError: unknown) => {
        if (isCancelled || requestId !== requestIdRef.current) {
          return;
        }
        console.error("Error loading group members:", nextError);
        setError(toErrorMessage(nextError, "Failed to load group members"));
      })
      .finally(() => {
        if (!isCancelled && requestId === requestIdRef.current) {
          setIsLoading(false);
        }
      });

    return () => {
      isCancelled = true;
    };
  }, [debouncedSearch, groupId, open, reloadToken]);

  useEffect(() => {
    if (!open) {
      setSearchInput("");
      setDebouncedSearch("");
      setAddDialogOpen(false);
    }
  }, [open]);

  const loadNextPage = async () => {
    if (!open || groupId === null || groupId === undefined || !hasNext || isLoading || isLoadingMore) {
      return;
    }

    const nextPage = page + 1;
    const requestId = ++requestIdRef.current;
    setIsLoadingMore(true);
    setError("");

    try {
      const response = await getGroupMembers(groupId, {
        q: debouncedSearch,
        page: nextPage,
        size: MEMBER_PAGE_SIZE,
      });
      if (requestId !== requestIdRef.current) {
        return;
      }
      setMembers((previous) => [...previous, ...(response.content || [])]);
      setPage(response.page ?? nextPage);
      setHasNext(Boolean(response.hasNext));
      setTotalElements(response.totalElements ?? 0);
    } catch (nextError: unknown) {
      if (requestId !== requestIdRef.current) {
        return;
      }
      console.error("Error loading more group members:", nextError);
      setError(toErrorMessage(nextError, "Failed to load more group members"));
    } finally {
      if (requestId === requestIdRef.current) {
        setIsLoadingMore(false);
      }
    }
  };

  const handleScroll = (event: React.UIEvent<HTMLDivElement>) => {
    const target = event.currentTarget;
    const distanceFromBottom = target.scrollHeight - target.scrollTop - target.clientHeight;
    if (distanceFromBottom <= SCROLL_LOAD_THRESHOLD_PX) {
      void loadNextPage();
    }
  };

  const refreshMembers = () => {
    setReloadToken((previous) => previous + 1);
  };

  return (
    <div className="group-member-list-wrapper group-details-section">
      <Box sx={{ display: "flex", alignItems: "center", justifyContent: "space-between", gap: 1, mb: 1.5 }}>
        <Typography variant="subtitle1" className="group-details-section-title" sx={{ mb: "0 !important" }}>
          Members
        </Typography>
        {canAddMembers && groupId !== null && groupId !== undefined ? (
          <Button
            size="small"
            variant="outlined"
            startIcon={<PersonAddAlt1OutlinedIcon />}
            onClick={() => setAddDialogOpen(true)}
            title="Add members"
          >
            Add
          </Button>
        ) : null}
      </Box>
      <Stack spacing={1.5}>
        <TextField
          label="Search members"
          value={searchInput}
          onChange={(event) => setSearchInput(event.target.value)}
          fullWidth
          size="small"
          placeholder="Search by username or full name"
          className="group-details-text-field"
          sx={groupDetailsTextFieldSx}
        />

        <Typography variant="subtitle2" className="group-details-section-subtitle">
          Roster ({isLoading ? "…" : `${members.length}${totalElements > members.length ? ` of ${totalElements}` : ""}`})
        </Typography>

        {error ? (
          <Alert severity="error">{error}</Alert>
        ) : null}

        {isLoading ? (
          <Box sx={{ display: "flex", justifyContent: "center", py: 3 }}>
            <CircularProgress size={24} />
          </Box>
        ) : (
          <Box onScroll={handleScroll} className="group-details-member-scroll">
            <List dense disablePadding>
              {members.map((member) => (
                <GroupMemberListItem
                  key={member.userId}
                  groupId={groupId as number | string}
                  member={member}
                  currentUsername={currentUsername}
                  currentUserRole={currentUserRole}
                  currentUserPermissions={currentUserPermissions}
                  onMemberKickedOut={(userId) => {
                    setMembers((previous) => previous.filter((item) => item.userId !== userId));
                    setTotalElements((previous) => Math.max(0, previous - 1));
                  }}
                  onMemberBanned={(userId) => {
                    setMembers((previous) => previous.filter((item) => item.userId !== userId));
                    setTotalElements((previous) => Math.max(0, previous - 1));
                    onMemberBanned?.();
                  }}
                  onMemberRoleUpdated={(updatedMember) => {
                    setMembers((previous) =>
                      previous.map((item) =>
                        item.userId === updatedMember.userId
                          ? { ...item, ...updatedMember }
                          : item,
                      ),
                    );
                  }}
                  onLeadershipTransferred={(newLeaderUserId) => {
                    setMembers((previous) =>
                      previous.map((item) => {
                        if (item.userId === newLeaderUserId) {
                          return { ...item, role: GROUP_ROLES.LEADER };
                        }
                        if (currentUsername && item.username === currentUsername) {
                          return { ...item, role: GROUP_ROLES.MEMBER };
                        }
                        return item;
                      }),
                    );
                    onLeadershipTransferred?.();
                  }}
                  onError={setError}
                />
              ))}
              {!members.length ? (
                <Typography variant="body2" className="group-details-muted-text" sx={{ px: 1.5, py: 2 }}>
                  {debouncedSearch ? "No members match your search." : "No members found."}
                </Typography>
              ) : null}
            </List>
            {isLoadingMore ? (
              <Box sx={{ display: "flex", justifyContent: "center", py: 1.5 }}>
                <CircularProgress size={20} />
              </Box>
            ) : null}
            {!hasNext && members.length > 0 ? (
              <Typography variant="caption" className="group-details-muted-text" sx={{ display: "block", px: 1.5, py: 1 }}>
                End of member list
              </Typography>
            ) : null}
          </Box>
        )}
      </Stack>

      {groupId !== null && groupId !== undefined ? (
        <AddGroupMemberDialog
          open={addDialogOpen}
          groupId={groupId}
          onClose={() => setAddDialogOpen(false)}
          onMembersAdded={refreshMembers}
        />
      ) : null}
    </div>
  );
}

function toErrorMessage(error: unknown, fallbackMessage: string): string {
  return error instanceof Error ? error.message : fallbackMessage;
}

export default GroupMemberList;
