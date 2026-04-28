import React, { useEffect, useState } from "react";
import { BrowserRouter, Routes, Route, Navigate, useNavigate } from "react-router-dom";
import { ThemeProvider, createTheme, Box, CircularProgress } from "@mui/material";
import { checkAuth, logout as apiLogout } from "./services/api";
import ChatPage from "./pages/ChatPage";
import LoginPage from "./pages/LoginPage";
import RegisterPage from "./pages/RegisterPage";
import { WebSocketProvider } from "./context/WebSocketProvider";
import "./App.css";

const theme = createTheme({
  palette: {
    primary: {
      main: "#667eea",
    },
    secondary: {
      main: "#764ba2",
    },
  },
  typography: {
    fontFamily: '"Roboto", "Helvetica", "Arial", sans-serif',
  },
});

function AppRoutes() {
  const navigate = useNavigate();
  const [authState, setAuthState] = useState({ checking: true, isAuth: false, username: null });

  useEffect(() => {
    const fetchAuth = async () => {
      try {
        const data = await checkAuth();
        if (data.authenticated) {
          setAuthState({ checking: false, isAuth: true, username: data.username });
        } else {
          setAuthState({ checking: false, isAuth: false, username: null });
        }
      } catch (error) {
        console.error("Auth check failed:", error);
        setAuthState({ checking: false, isAuth: false, username: null });
      }
    };

    fetchAuth();
  }, []);

  const handleLoginSuccess = (username) => {
    setAuthState({ checking: false, isAuth: true, username });
  };

  const handleLogout = async () => {
    try {
      await apiLogout();
    } catch (error) {
      console.error("Logout failed:", error);
    } finally {
      setAuthState({ checking: false, isAuth: false, username: null });
      navigate("/login", { replace: true });
    }
  };

  if (authState.checking) {
    return (
      <Box
        sx={{
          display: "flex",
          justifyContent: "center",
          alignItems: "center",
          height: "100vh",
          background: "linear-gradient(135deg, #667eea 0%, #764ba2 100%)",
        }}
      >
        <CircularProgress sx={{ color: "white" }} />
      </Box>
    );
  }

  const RequireAuth = ({ children }) => {
    if (!authState.isAuth) {
      return <Navigate to="/login" replace />;
    }
    return children;
  };

  return (
    <Routes>
      <Route
        path="/"
        element={<Navigate to={authState.isAuth ? "/group/public" : "/login"} replace />}
      />
      <Route
        path="/login"
        element={authState.isAuth ? <Navigate to="/group/public" replace /> : <LoginPage onLoginSuccess={handleLoginSuccess} />}
      />
      <Route
        path="/register"
        element={authState.isAuth ? <Navigate to="/group/public" replace /> : <RegisterPage />}
      />
      <Route
        path="/group"
        element={<RequireAuth><Navigate to="/group/public" replace /></RequireAuth>}
      />
      <Route
        path="/group/:groupId"
        element={
          <RequireAuth>
            <ChatPage username={authState.username} onLogout={handleLogout} />
          </RequireAuth>
        }
      />
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}

function App() {
  return (
    <ThemeProvider theme={theme}>
      <BrowserRouter>
        <WebSocketProvider>
          <AppRoutes />
        </WebSocketProvider>
      </BrowserRouter>
    </ThemeProvider>
  );
}

export default App;
