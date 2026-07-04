import React, { useEffect, useMemo, useState } from "react";
import { BrowserRouter, Routes, Route, Navigate, useNavigate } from "react-router-dom";
import { ThemeProvider, createTheme, Box, CircularProgress } from "@mui/material";
import { checkAuth, logout as apiLogout } from "./services/api";
import ChatPage from "./pages/ChatPage";
import LoginPage from "./pages/LoginPage";
import RegisterPage from "./pages/RegisterPage";
import { DEFAULT_THEME_ID, THEME_STORAGE_KEY, resolveThemeTokens, themeOptions } from "./theme/tokens";
import { WebSocketProvider } from "./context/WebSocketProvider";
import type { AuthState } from "./types/auth";
import type { ResolvedTheme, ThemeId } from "./types/theme";
import "./App.css";

interface AppRoutesProps {
  selectedThemeId: ThemeId;
  onThemeChange: (themeId: ThemeId) => void;
  resolvedTheme: ResolvedTheme;
}

interface RequireAuthProps {
  children: React.ReactNode;
}

function AppRoutes({ selectedThemeId, onThemeChange, resolvedTheme }: AppRoutesProps) {
  const navigate = useNavigate();
  const [authState, setAuthState] = useState<AuthState>({
    checking: true,
    isAuth: false,
    username: null,
  });

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

  const handleLoginSuccess = (username: string) => {
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
          background: resolvedTheme.gradientTokens.brand,
        }}
      >
        <CircularProgress sx={{ color: "white" }} />
      </Box>
    );
  }

  const RequireAuth = ({ children }: RequireAuthProps) => {
    if (!authState.isAuth) {
      return <Navigate to="/login" replace />;
    }
    return <>{children}</>;
  };

  return (
    <WebSocketProvider username={authState.isAuth ? authState.username : null}>
      <Routes>
      <Route path="/" element={<Navigate to={authState.isAuth ? "/group/public" : "/login"} replace />} />
      <Route
        path="/login"
        element={
          authState.isAuth ? <Navigate to="/group/public" replace /> : <LoginPage onLoginSuccess={handleLoginSuccess} />
        }
      />
      <Route path="/register" element={authState.isAuth ? <Navigate to="/group/public" replace /> : <RegisterPage />} />
      <Route
        path="/group"
        element={
          <RequireAuth>
            <Navigate to="/group/public" replace />
          </RequireAuth>
        }
      />
      <Route
        path="/group/:groupId"
        element={
          <RequireAuth>
            <ChatPage
              username={authState.username}
              onLogout={handleLogout}
              selectedThemeId={selectedThemeId}
              onThemeChange={onThemeChange}
              themeOptions={themeOptions}
            />
          </RequireAuth>
        }
      />
      <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </WebSocketProvider>
  );
}

function App() {
  const [selectedThemeId, setSelectedThemeId] = useState<ThemeId>(
    () => (localStorage.getItem(THEME_STORAGE_KEY) || DEFAULT_THEME_ID) as ThemeId,
  );

  const resolvedTheme = useMemo(() => resolveThemeTokens(selectedThemeId), [selectedThemeId]);

  const theme = useMemo(
    () =>
      createTheme({
        palette: {
          primary: {
            main: resolvedTheme.colorTokens.primary,
          },
          secondary: {
            main: resolvedTheme.colorTokens.primaryDark,
          },
        },
        typography: {
          fontFamily: '"Roboto", "Helvetica", "Arial", sans-serif',
        },
      }),
    [resolvedTheme],
  );

  useEffect(() => {
    localStorage.setItem(THEME_STORAGE_KEY, resolvedTheme.id);
  }, [resolvedTheme.id]);

  useEffect(() => {
    const root = document.documentElement;
    Object.entries(resolvedTheme.cssVars).forEach(([name, value]) => {
      root.style.setProperty(name, value);
    });
  }, [resolvedTheme]);

  return (
    <ThemeProvider theme={theme}>
      <BrowserRouter>
        <AppRoutes
          selectedThemeId={resolvedTheme.id}
          onThemeChange={setSelectedThemeId}
          resolvedTheme={resolvedTheme}
        />
      </BrowserRouter>
    </ThemeProvider>
  );
}

export default App;
