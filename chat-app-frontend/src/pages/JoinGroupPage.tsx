import React, { useEffect, useState } from "react";
import { Link as RouterLink, useNavigate, useParams } from "react-router-dom";
import {
  Alert,
  Box,
  Button,
  Card,
  CircularProgress,
  Container,
  Stack,
  TextField,
  Typography,
} from "@mui/material";
import { joinGroupByToken } from "../services/api";
import "./auth.css";

interface JoinGroupPageProps {
  isAuthenticated: boolean;
}

function JoinGroupPage({ isAuthenticated }: JoinGroupPageProps) {
  const navigate = useNavigate();
  const { token: routeToken } = useParams<{ token?: string }>();
  const [tokenInput, setTokenInput] = useState(routeToken || "");
  const [error, setError] = useState("");
  const [isJoining, setIsJoining] = useState(false);
  const [autoJoinAttempted, setAutoJoinAttempted] = useState(false);

  useEffect(() => {
    document.title = "Join Group | Chat App";
  }, []);

  useEffect(() => {
    if (routeToken) {
      setTokenInput(routeToken);
    }
  }, [routeToken]);

  useEffect(() => {
    if (!isAuthenticated || !routeToken || autoJoinAttempted) {
      return;
    }
    setAutoJoinAttempted(true);
    void submitJoin(routeToken);
    // Intentionally run once per route token while authenticated.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [autoJoinAttempted, isAuthenticated, routeToken]);

  const submitJoin = async (rawToken: string) => {
    const token = rawToken.trim();
    if (!token) {
      setError("Paste a join token to continue");
      return;
    }

    if (!isAuthenticated) {
      const redirectPath = `/join/${encodeURIComponent(token)}`;
      navigate(`/login?redirect=${encodeURIComponent(redirectPath)}`, { replace: true });
      return;
    }

    setIsJoining(true);
    setError("");
    try {
      const membership = await joinGroupByToken(token);
      if (!membership.groupId) {
        throw new Error("Joined successfully, but group id was missing from the response");
      }
      navigate(`/group/${membership.groupId}`, {
        replace: true,
        state: {
          joinedViaLink: true,
          groupName: membership.groupName || undefined,
        },
      });
    } catch (joinError: unknown) {
      console.error("Error joining group via token:", joinError);
      setError(joinError instanceof Error ? joinError.message : "Failed to join group");
    } finally {
      setIsJoining(false);
    }
  };

  const handleSubmit = (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    void submitJoin(tokenInput);
  };

  return (
    <div className="join-group-page-wrapper">
      <Box
        sx={{
          background: "var(--gradient-brand)",
          minHeight: "100vh",
          display: "flex",
          alignItems: "center",
          justifyContent: "center",
          py: 2,
        }}
      >
        <Container maxWidth="sm">
          <Card
            sx={{
              p: 4,
              borderRadius: 2,
              boxShadow: "0 8px 32px 0 var(--color-shadow-brand)",
            }}
          >
            <Box sx={{ textAlign: "center", mb: 3 }}>
              <Typography variant="h4" sx={{ fontWeight: "bold", mb: 1 }}>
                Join a group
              </Typography>
              <Typography variant="body1" color="textSecondary">
                Use a join link token shared by a group member.
              </Typography>
            </Box>

            {error ? (
              <Alert severity="error" sx={{ mb: 2 }}>
                {error}
              </Alert>
            ) : null}

            {isJoining && routeToken ? (
              <Box sx={{ display: "flex", justifyContent: "center", py: 3 }}>
                <CircularProgress />
              </Box>
            ) : (
              <form onSubmit={handleSubmit}>
                <Stack spacing={2} sx={{ mb: 3 }}>
                  <TextField
                    label="Join token"
                    value={tokenInput}
                    onChange={(event) => setTokenInput(event.target.value)}
                    placeholder="Paste join token"
                    fullWidth
                    required
                    disabled={isJoining}
                    autoComplete="off"
                  />
                </Stack>

                <Button
                  type="submit"
                  fullWidth
                  variant="contained"
                  disabled={isJoining || !tokenInput.trim()}
                  sx={{ py: 1.5, mb: 2 }}
                >
                  {isJoining ? <CircularProgress size={24} sx={{ color: "var(--color-surface)" }} /> : "Join group"}
                </Button>
              </form>
            )}

            <Box sx={{ textAlign: "center" }}>
              {isAuthenticated ? (
                <Button component={RouterLink} to="/group/public">
                  Back to chats
                </Button>
              ) : (
                <Typography variant="body2">
                  Already have an account?{" "}
                  <RouterLink
                    to={
                      tokenInput.trim()
                        ? `/login?redirect=${encodeURIComponent(`/join/${encodeURIComponent(tokenInput.trim())}`)}`
                        : "/login?redirect=%2Fjoin"
                    }
                    style={{
                      color: "var(--color-link)",
                      textDecoration: "none",
                      fontWeight: "bold",
                    }}
                  >
                    Login
                  </RouterLink>
                </Typography>
              )}
            </Box>
          </Card>
        </Container>
      </Box>
    </div>
  );
}

export default JoinGroupPage;
