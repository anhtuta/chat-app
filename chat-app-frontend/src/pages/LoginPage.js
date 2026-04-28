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
import { login } from "../services/api";

function LoginPage({ onLoginSuccess }) {
    const navigate = useNavigate();
    const [username, setUsername] = useState("");
    const [password, setPassword] = useState("");
    const [error, setError] = useState(null);
    const [loading, setLoading] = useState(false);

    useEffect(() => {
        document.title = "Login - Chat App";
    }, []);

    const handleSubmit = async (e) => {
        e.preventDefault();
        setError(null);

        if (!username.trim() || !password) {
            setError("Please fill in all fields");
            return;
        }

        setLoading(true);
        try {
            const result = await login(username.trim(), password);
            if (result.success) {
                onLoginSuccess(result.username);
                navigate("/group/public", { replace: true });
            } else {
                setError(result.message || "Login failed");
            }
        } catch (err) {
            setError("An error occurred. Please try again.");
        } finally {
            setLoading(false);
        }
    };

    return (
        <Box
            sx={{
                background: "linear-gradient(135deg, #667eea 0%, #764ba2 100%)",
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
                        boxShadow: "0 8px 32px 0 rgba(31, 38, 135, 0.37)",
                    }}
                >
                    <Box sx={{ textAlign: "center", mb: 3 }}>
                        <Typography variant="h4" sx={{ fontWeight: "bold", mb: 1 }}>
                            💬 Chat App
                        </Typography>
                        <Typography variant="body1" color="textSecondary">
                            Login to continue
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
                                onChange={(e) => setUsername(e.target.value)}
                                autoComplete="username"
                                required
                                fullWidth
                                variant="outlined"
                            />
                            <TextField
                                label="Password"
                                type="password"
                                value={password}
                                onChange={(e) => setPassword(e.target.value)}
                                autoComplete="current-password"
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
                                <CircularProgress size={24} sx={{ color: "white" }} />
                            ) : (
                                "Login"
                            )}
                        </Button>
                    </form>

                    <Box sx={{ textAlign: "center" }}>
                        <Typography variant="body2">
                            Don't have an account?{" "}
                            <Link
                                to="/register"
                                style={{
                                    color: "#667eea",
                                    textDecoration: "none",
                                    fontWeight: "bold",
                                }}
                            >
                                Register
                            </Link>
                        </Typography>
                    </Box>
                </Card>
            </Container>
        </Box>
    );
}

export default LoginPage;
