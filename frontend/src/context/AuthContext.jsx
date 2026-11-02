import React, { createContext, useContext, useEffect, useState } from "react";
import { api } from "../api/client.js";

const AuthContext = createContext(null);

export function AuthProvider({ children }) {
  const [user, setUser] = useState(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    const stored = localStorage.getItem("user");
    try { if (stored) setUser(JSON.parse(stored)); } catch { localStorage.removeItem("user"); }
    if (!localStorage.getItem("token")) { setLoading(false); return; }
    api.me().then((current) => {
      setUser(current); localStorage.setItem("user", JSON.stringify(current));
    }).catch((error) => {
      if ([401, 403].includes(error.status)) { localStorage.removeItem("token"); localStorage.removeItem("user"); setUser(null); }
    }).finally(() => setLoading(false));
  }, []);

  function persist(authResponse) {
    localStorage.setItem("token", authResponse.token);
    const u = {
      id: authResponse.userId,
      username: authResponse.username,
      role: authResponse.role,
    };
    localStorage.setItem("user", JSON.stringify(u));
    setUser(u);
  }

  async function login(username, password) {
    const res = await api.login({ username, password });
    persist(res);
  }

  function logout() {
    localStorage.removeItem("token");
    localStorage.removeItem("user");
    setUser(null);
  }

  return (
    <AuthContext.Provider value={{ user, loading, login, logout }}>
      {children}
    </AuthContext.Provider>
  );
}

export function useAuth() {
  return useContext(AuthContext);
}
