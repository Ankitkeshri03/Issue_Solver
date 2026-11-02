import React, { useEffect, useState } from "react";
import { Link, useLocation } from "react-router-dom";
import { api } from "../api/client.js";
import { useAuth } from "../context/AuthContext.jsx";

export default function ResetPassword() {
  const { logout } = useAuth();
  const location = useLocation();
  const [token, setToken] = useState(() => new URLSearchParams(location.hash.slice(1)).get("token") || "");
  const [form, setForm] = useState({ email: "", password: "", confirmPassword: "" });
  const [error, setError] = useState("");
  const [message, setMessage] = useState("");
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    if (!location.hash) return;
    // A newly opened email link can reuse this route without remounting the page.
    setToken(new URLSearchParams(location.hash.slice(1)).get("token") || "");
    setError("");
    setMessage("");
    setForm({ email: "", password: "", confirmPassword: "" });
    // Keep the secret in this page's memory, outside browser history and HTTP URLs.
    window.history.replaceState(window.history.state, "", location.pathname + location.search);
  }, [location.hash, location.pathname, location.search]);

  async function handleSubmit(event) {
    event.preventDefault();
    setError("");
    if (form.password !== form.confirmPassword) {
      setError("Passwords do not match");
      return;
    }
    if (new TextEncoder().encode(form.password).length > 72) {
      setError("Password must be at most 72 bytes");
      return;
    }
    setBusy(true);
    try {
      const response = await api.resetPassword({ ...form, email: form.email.trim(), token });
      logout();
      setForm({ email: "", password: "", confirmPassword: "" });
      setMessage(response.message);
    } catch (err) {
      setError(err.message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="max-w-sm mx-auto mt-16 bg-white border rounded-lg p-6 shadow-sm">
      <h1 className="text-lg font-semibold mb-4">Choose a new password</h1>
      {message ? (
        <p role="status" className="text-sm text-green-700">{message}</p>
      ) : !/^[A-Za-z0-9_-]{43}$/.test(token) ? (
        <p role="alert" className="text-sm text-red-600">This reset link is missing or invalid. Request a new link from the sign-in page.</p>
      ) : (
        <>
          <p className="text-sm text-slate-500 mb-4">Enter the email you used to register. Your new password must have at least 8 characters.</p>
          <form onSubmit={handleSubmit} className="space-y-3">
            <input aria-label="Registered email" type="email" autoComplete="email" placeholder="Registered email"
              className="w-full border rounded px-3 py-2 text-sm" required maxLength={255}
              value={form.email} onChange={(e) => setForm({ ...form, email: e.target.value })} />
            <input aria-label="New password" type="password" autoComplete="new-password" placeholder="New password"
              className="w-full border rounded px-3 py-2 text-sm" required minLength={8} maxLength={72}
              value={form.password} onChange={(e) => setForm({ ...form, password: e.target.value })} />
            <input aria-label="Confirm new password" type="password" autoComplete="new-password" placeholder="Confirm new password"
              className="w-full border rounded px-3 py-2 text-sm" required minLength={8} maxLength={72}
              value={form.confirmPassword} onChange={(e) => setForm({ ...form, confirmPassword: e.target.value })} />
            {error && <p role="alert" className="text-sm text-red-600">{error}</p>}
            <button disabled={busy} className="w-full bg-slate-900 text-white rounded px-3 py-2 text-sm disabled:opacity-50">
              {busy ? "Changing password…" : "Change password"}
            </button>
          </form>
        </>
      )}
      <Link to="/login" className="block text-sm text-slate-500 mt-4 hover:underline">
        {message ? "Sign in" : "Back to sign in / request a new link"}
      </Link>
    </div>
  );
}
