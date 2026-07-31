import React, { useEffect, useState } from "react";
import { useNavigate, Link } from "react-router-dom";
import {
    Container,
    Card,
    TextField,
    Button,
    Typography,
    Box,
    Alert,
    CircularProgress,
    Stack,
} from "@mui/material";
import { register } from "../services/api";

function RegisterPage() {
    const navigate = useNavigate();
    const [username, setUsername] = useState("");
    const [password, setPassword] = useState("");
    const [confirmPassword, setConfirmPassword] = useState("");
    const [error, setError] = useState<string | null>(null);
    const [loading, setLoading] = useState(false);

    useEffect(() => {
        document.title = "Register | Chat App";
    }, []);

    const handleSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
        event.preventDefault();
        setError(null);

        if (!username.trim() || !password || !confirmPassword) {
            setError("Please fill in all fields");
            return;
        }

        if (password !== confirmPassword) {
            setError("Passwords do not match");
            return;
        }

        if (password.length < 3) {
            setError("Password must be at least 3 characters long");
            return;
        }

        setLoading(true);
        try {
            const result = await register(username.trim(), password);
            if (result.success) {
                navigate("/login", { replace: true });
            } else {
                setError(result.message || "Registration failed");
            }
        } catch (err) {
            setError("An error occurred. Please try again.");
        } finally {
            setLoading(false);
        }
    };

    return (
        <div className="register-page-wrapper">
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
                                💬 Chat App
                            </Typography>
                            <Typography variant="body1" color="textSecondary">
                                Create a new account
                            </Typography>
                        </Box>

                        {error && (
                            <Alert severity="error" sx={{ mb: 2 }}>
                                {error}
                            </Alert>
                        )}

                        <form onSubmit={handleSubmit}>
                            <Stack spacing={2} sx={{ mb: 3 }}>
                                <TextField
                                    label="Username"
                                    type="text"
                                    value={username}
                                    onChange={(event) => setUsername(event.target.value)}
                                    autoComplete="username"
                                    required
                                    fullWidth
                                    variant="outlined"
                                />
                                <TextField
                                    label="Password"
                                    type="password"
                                    value={password}
                                    onChange={(event) => setPassword(event.target.value)}
                                    autoComplete="new-password"
                                    required
                                    fullWidth
                                    variant="outlined"
                                />
                                <TextField
                                    label="Confirm Password"
                                    type="password"
                                    value={confirmPassword}
                                    onChange={(event) => setConfirmPassword(event.target.value)}
                                    autoComplete="new-password"
                                    required
                                    fullWidth
                                    variant="outlined"
                                />
                            </Stack>

                            <Button
                                type="submit"
                                disabled={loading}
                                fullWidth
                                variant="contained"
                                sx={{ py: 1.5, mb: 2 }}
                            >
                                {loading ? (
                                    <CircularProgress size={24} sx={{ color: "var(--color-surface)" }} />
                                ) : (
                                    "Register"
                                )}
                            </Button>
                        </form>

                        <Box sx={{ textAlign: "center" }}>
                            <Typography variant="body2">
                                Already have an account?{" "}
                                <Link
                                    to="/login"
                                    style={{
                                        color: "var(--color-link)",
                                        textDecoration: "none",
                                        fontWeight: "bold",
                                    }}
                                >
                                    Login
                                </Link>
                            </Typography>
                        </Box>
                    </Card>
                </Container>
            </Box>
        </div>
    );
}

export default RegisterPage;
