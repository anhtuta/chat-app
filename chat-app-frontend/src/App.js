import React, { useEffect, useState } from "react";
import { HashRouter, Routes, Route, Navigate } from "react-router-dom";
import { checkAuth, logout as apiLogout } from "./services/api";
import ChatContainer from "./components/ChatContainer";
import "./App.css";

function App() {
  const [isAuthenticated, setIsAuthenticated] = useState(null);
  const [username, setUsername] = useState(null);

  useEffect(() => {
    checkAuthentication();
  }, []);

  const checkAuthentication = async () => {
    try {
      const data = await checkAuth();
      if (data.authenticated) {
        setIsAuthenticated(true);
        setUsername(data.username);
      } else {
        setIsAuthenticated(false);
      }
    } catch (error) {
      console.error("Auth check failed:", error);
      setIsAuthenticated(false);
    }
  };

  const handleLogout = async () => {
    try {
      await apiLogout();
      setIsAuthenticated(false);
      setUsername(null);
      window.location.href = "/login.html";
    } catch (error) {
      console.error("Logout failed:", error);
      window.location.href = "/login.html";
    }
  };

  if (isAuthenticated === null) {
    // Still checking authentication
    return (
      <div
        style={{
          display: "flex",
          justifyContent: "center",
          alignItems: "center",
          height: "100vh",
          background: "linear-gradient(135deg, #667eea 0%, #764ba2 100%)",
        }}
      >
        <div style={{ color: "white", fontSize: "18px" }}>Loading...</div>
      </div>
    );
  }

  if (!isAuthenticated) {
    // Redirect to login page
    window.location.href = "/login.html";
    return null;
  }

  return (
    <HashRouter>
      <Routes>
        <Route path="/" element={<ChatContainer username={username} onLogout={handleLogout} />} />
        <Route path="/chat" element={<ChatContainer username={username} onLogout={handleLogout} />} />
        <Route path="/groups/:groupId" element={<ChatContainer username={username} onLogout={handleLogout} />} />
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </HashRouter>
  );
}

export default App;
