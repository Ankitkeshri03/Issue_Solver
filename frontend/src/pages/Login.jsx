import React, { useState } from "react";
import { useLocation, useNavigate, useSearchParams } from "react-router-dom";
import { useAuth } from "../context/AuthContext.jsx";
import { api } from "../api/client.js";

export default function Login() {
  const { login } = useAuth(); const navigate = useNavigate(); const location = useLocation();
  const [searchParams] = useSearchParams();
  const [mode, setMode] = useState("login");
  const [form, setForm] = useState({ username: "", email: "", password: "" });
  const [error, setError] = useState(""); const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState(location.state?.activationComplete ? "Account activated. Sign in with your email or username and chosen password." : "");
  function switchMode(nextMode) {
    setMode(nextMode); setError(""); setMessage(""); setForm((current) => ({ ...current, password: "" }));
  }
  async function handleSubmit(event) {
    event.preventDefault(); setError(""); setMessage(""); setBusy(true);
    try {
      if (mode === "forgot") { const response = await api.forgotPassword(form.email.trim()); setMessage(response.message); return; }
      await login(form.username, form.password);
      navigate(searchParams.get("next") === "/invitations" ? "/invitations" : "/");
    } catch (err) { setError(err.message); } finally { setBusy(false); }
  }
  return <div className="max-w-sm mx-auto mt-16 bg-white border rounded-lg p-6 shadow-sm space-y-4">
    <h1 className="text-lg font-semibold">{mode === "login" ? "Sign in" : "Forgot password?"}</h1>
    <p className="text-sm text-slate-500">{mode === "login" ? "One sign-in for every team role. Your company and project memberships determine your access." : "Enter your registered email. We’ll send you a link to choose a new password."}</p>
    <form onSubmit={handleSubmit} className="space-y-3">
      {mode === "login" ? <>
        <input className="w-full border rounded px-3 py-2 text-sm" aria-label="Email or username" placeholder="Email or username" autoComplete="username" required value={form.username} onChange={(e) => setForm({ ...form, username: e.target.value })} />
        <input className="w-full border rounded px-3 py-2 text-sm" type="password" aria-label="Password" placeholder="Password" autoComplete="current-password" required maxLength={72} value={form.password} onChange={(e) => setForm({ ...form, password: e.target.value })} />
      </> : <input className="w-full border rounded px-3 py-2 text-sm" aria-label="Email" type="email" placeholder="Email" autoComplete="email" required value={form.email} onChange={(e) => setForm({ ...form, email: e.target.value })} />}
      {error && <p role="alert" className="text-red-600 text-sm">{error}</p>}
      {message && <p role="status" className="text-green-700 text-sm">{message}</p>}
      <button disabled={busy} className="w-full bg-slate-900 text-white rounded px-3 py-2 text-sm disabled:opacity-50">{busy ? "Please wait…" : mode === "login" ? "Sign in" : "Send reset link"}</button>
    </form>
    <button disabled={busy} className="block text-sm text-slate-500 hover:underline" onClick={() => switchMode(mode === "login" ? "forgot" : "login")}>{mode === "login" ? "Forgot password?" : "Back to sign in"}</button>
    <p className="text-xs text-slate-500">New here? Open your email invitation to activate your account. Ask your company admin or project manager if you haven’t received one.</p>
  </div>;
}
