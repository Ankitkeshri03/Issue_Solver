import React, { createContext, useContext, useEffect, useState } from "react";
import { api } from "../api/client.js";

const AuthContext = createContext(null);

export function AuthProvider({ children }) {
  const [user, setUser] = useState(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    const stored = localStorage.getItem("user");
    if (stored) setUser(JSON.parse(stored));
    setLoading(false);
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

  async function register(username, email, password, role) {
    const res = await api.register({ username, email, password, role });
    persist(res);
  }

  function logout() {
    localStorage.removeItem("token");
    localStorage.removeItem("user");
    setUser(null);
  }

  return (
    <AuthContext.Provider value={{ user, loading, login, register, logout }}>
      {children}
    </AuthContext.Provider>
  );
}

export function useAuth() {
  return useContext(AuthContext);
}
