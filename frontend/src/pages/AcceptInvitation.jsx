import React, { useEffect, useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import { useAuth } from "../context/AuthContext.jsx";
import { api } from "../api/client.js";
import { roleLabel } from "../lib/roles.js";

export default function AcceptInvitation() {
  const { user, loading, logout } = useAuth(); const navigate = useNavigate();
  const [token] = useState(() => {
    const incoming = new URLSearchParams(window.location.hash.slice(1)).get("token");
    if (incoming) { sessionStorage.setItem("pendingInvitation", incoming); window.history.replaceState(null, "", window.location.pathname); }
    return incoming || sessionStorage.getItem("pendingInvitation") || "";
  });
  const [details, setDetails] = useState(null); const [error, setError] = useState(""); const [busy, setBusy] = useState(false);
  const [account, setAccount] = useState({ username: "", password: "", confirmPassword: "" });
  useEffect(() => {
    let active = true;
    if (token) api.previewInvitation(token).then((value) => { if (active) setDetails(value); }).catch((err) => { if (active) setError(err.message); });
    return () => { active = false; };
  }, [token]);
  async function accept() {
    setBusy(true); setError("");
    try {
      const workspace = await api.acceptInvitation(token); sessionStorage.removeItem("pendingInvitation");
      localStorage.setItem("activeWorkspaceId", String(workspace.id)); navigate("/");
    } catch (err) { setError(err.message); } finally { setBusy(false); }
  }
  async function activate(event) {
    event.preventDefault(); setError("");
    if (account.password !== account.confirmPassword) { setError("Passwords do not match"); return; }
    setBusy(true);
    try {
      await api.activateInvitation({ token, email: details.email, ...account });
      setAccount({ username: "", password: "", confirmPassword: "" }); sessionStorage.removeItem("pendingInvitation");
      navigate("/login", { replace: true, state: { activationComplete: true } });
    } catch (err) { setError(err.message); } finally { setBusy(false); }
  }
  return <div className="max-w-lg mx-auto bg-white border rounded-lg p-6 space-y-4">
    <h1 className="text-xl font-semibold">Activate your team access</h1>
    {error && <p role="alert" className="text-red-600 text-sm">{error}</p>}
    {!token ? <p className="text-sm">Open the private invitation link your company admin or project manager sent you.</p> : loading ? <p>Checking your session…</p> : details ? <>
      <p className="text-sm">You’re invited to <strong>{details.workspaceName}</strong>{details.projectName ? <> · {details.projectName}</> : null} as <strong>{roleLabel(details.role)}</strong>.</p>
      <p className="text-sm text-slate-600">Invitation for {details.email} · Expires {new Date(details.expiresAt).toLocaleString()}</p>
      {user ? <>
        <p className="text-sm">Accept using the account for this email address. This does not change your password.</p>
        <button disabled={busy} onClick={accept} className="bg-slate-900 text-white rounded px-4 py-2 text-sm disabled:opacity-50">Accept invitation</button>
        <button disabled={busy} onClick={logout} className="block text-sm text-slate-500 underline">Use a different account</button>
      </> : details.accountExists ? <>
        <p className="text-sm">You already have an account. Sign in with the invited email to accept this membership.</p>
        <Link className="inline-block bg-slate-900 text-white rounded px-4 py-2 text-sm" to="/login?next=/invitations">Sign in to accept</Link>
      </> : <form onSubmit={activate} className="space-y-3">
        <p className="text-sm text-slate-600">Choose your own username and password. Your role comes from this invitation.</p>
        <label className="block text-sm">Email<input type="email" readOnly autoComplete="email" className="w-full border bg-slate-50 rounded px-3 py-2 mt-1" value={details.email} /></label>
        <label className="block text-sm">Username<input required autoComplete="username" minLength={3} maxLength={100} className="w-full border rounded px-3 py-2 mt-1" value={account.username} onChange={(e) => setAccount({ ...account, username: e.target.value })} /></label>
        <label className="block text-sm">Password<input required type="password" autoComplete="new-password" minLength={8} maxLength={72} className="w-full border rounded px-3 py-2 mt-1" value={account.password} onChange={(e) => setAccount({ ...account, password: e.target.value })} /></label>
        <label className="block text-sm">Confirm password<input required type="password" autoComplete="new-password" minLength={8} maxLength={72} className="w-full border rounded px-3 py-2 mt-1" value={account.confirmPassword} onChange={(e) => setAccount({ ...account, confirmPassword: e.target.value })} /></label>
        <button disabled={busy} className="bg-slate-900 text-white rounded px-4 py-2 text-sm disabled:opacity-50">{busy ? "Activating…" : "Activate account"}</button>
      </form>}
    </> : !error && <p>Checking invitation…</p>}
    <Link to="/login" className="block text-sm text-slate-500 underline">Back to sign in</Link>
  </div>;
}
