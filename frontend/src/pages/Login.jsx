import React, { useState } from "react";
import { useNavigate } from "react-router-dom";
import { useAuth } from "../context/AuthContext.jsx";

export default function Login() {
  const { login, register } = useAuth();
  const navigate = useNavigate();
  const [mode, setMode] = useState("login");
  const [form, setForm] = useState({ username: "", email: "", password: "", role: "DEVELOPER" });
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);

  async function handleSubmit(e) {
    e.preventDefault();
    setError("");
    setBusy(true);
    try {
      if (mode === "login") {
        await login(form.username, form.password);
      } else {
        await register(form.username, form.email, form.password, form.role);
      }
      navigate("/");
    } catch (err) {
      setError(err.message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="max-w-sm mx-auto mt-16 bg-white border rounded-lg p-6 shadow-sm">
      <h1 className="text-lg font-semibold mb-4">{mode === "login" ? "Sign in" : "Create account"}</h1>
      <form onSubmit={handleSubmit} className="space-y-3">
        <input
          className="w-full border rounded px-3 py-2 text-sm"
          placeholder="Username"
          value={form.username}
          onChange={(e) => setForm({ ...form, username: e.target.value })}
          required
        />
        {mode === "register" && (
          <input
            className="w-full border rounded px-3 py-2 text-sm"
            placeholder="Email"
            type="email"
            value={form.email}
            onChange={(e) => setForm({ ...form, email: e.target.value })}
            required
          />
        )}
        <input
          className="w-full border rounded px-3 py-2 text-sm"
          placeholder="Password"
          type="password"
          value={form.password}
          onChange={(e) => setForm({ ...form, password: e.target.value })}
          required
        />
        {mode === "register" && (
          <select
            className="w-full border rounded px-3 py-2 text-sm"
            value={form.role}
            onChange={(e) => setForm({ ...form, role: e.target.value })}
          >
            <option value="DEVELOPER">Developer</option>
            <option value="QA">QA</option>
          </select>
        )}
        {error && <p className="text-red-600 text-sm">{error}</p>}
        <button
          disabled={busy}
          className="w-full bg-slate-900 text-white rounded px-3 py-2 text-sm disabled:opacity-50"
        >
          {mode === "login" ? "Sign in" : "Register"}
        </button>
      </form>
      <button
        className="text-sm text-slate-500 mt-4 hover:underline"
        onClick={() => setMode(mode === "login" ? "register" : "login")}
      >
        {mode === "login" ? "Need an account? Register" : "Already have an account? Sign in"}
      </button>
    </div>
  );
}
