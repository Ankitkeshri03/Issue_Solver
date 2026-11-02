import React, { useEffect, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { api } from "../api/client.js";
import { PROJECT_ROLES, roleLabel } from "../lib/roles.js";

export default function ProjectDetail() {
  const { id } = useParams();
  const [project, setProject] = useState(null); const [repos, setRepos] = useState([]);
  const [members, setMembers] = useState([]); const [workspaceMembers, setWorkspaceMembers] = useState([]);
  const [github, setGithub] = useState(null); const [available, setAvailable] = useState([]); const [token, setToken] = useState("");
  const [connection, setConnection] = useState({ owner: "", repo: "" });
  const [membership, setMembership] = useState({ userId: "", role: "DEVELOPER" });
  const [invitations, setInvitations] = useState([]); const [invitationUrl, setInvitationUrl] = useState("");
  const [invite, setInvite] = useState({ email: "", role: "DEVELOPER", sendEmail: true });
  const [error, setError] = useState(""); const [message, setMessage] = useState(""); const [busy, setBusy] = useState(false);
  useEffect(() => { setInvitationUrl(""); load(); }, [id]);
  async function load() {
    try {
      const item = await api.getProject(id); setProject(item);
      const [connections, people] = await Promise.all([api.listRepos(id), api.projectMembers(id)]);
      setRepos(connections); setMembers(people);
      if (item.workspaceAdmin || item.myRole === "PROJECT_MANAGER") {
        const [candidates, pending] = await Promise.all([api.availableProjectMembers(id), api.projectInvitations(id)]);
        setWorkspaceMembers(candidates); setInvitations(pending);
      }
      if (item.workspaceAdmin) {
        const status = await api.getGithubStatus(); setGithub(status);
        if (status.connected) setAvailable(await api.listAvailableGithubRepos());
      }
    } catch (err) { setError(err.message); }
  }
  async function action(fn) {
    setBusy(true); setError(""); setMessage("");
    try { await fn(); await load(); } catch (err) { setError(err.message); } finally { setBusy(false); }
  }
  if (!project) return <p>{error || "Loading project…"}</p>;
  const canManageTeam = project.workspaceAdmin || project.myRole === "PROJECT_MANAGER";
  const teamRoles = project.workspaceAdmin ? PROJECT_ROLES : ["QA", "DEVELOPER"];
  return <div className="space-y-6">
    <Link to="/" className="text-sm text-slate-500 hover:underline">← Workspaces</Link>
    <div><p className="text-xs text-slate-500">{project.workspaceName}</p><h1 className="text-2xl font-semibold">{project.name}</h1>
      <p className="text-sm text-slate-500 mt-1">{roleLabel(project.myRole)}{project.workspaceAdmin && project.myRole !== "WORKSPACE_ADMIN" ? " · Workspace Admin" : ""}</p>
      {project.description && <p className="text-sm mt-2">{project.description}</p>}
    </div>
    {error && <p role="alert" className="text-sm text-red-600">{error}</p>}{message && <p role="status" className="text-sm text-green-700">{message}</p>}
    {project.myRole === "PROJECT_MANAGER" && <p className="text-sm text-slate-600">Open a repository to review reported issues, set priorities, and assign developers from this project.</p>}
    {project.myRole === "QA" && <p className="text-sm text-slate-600">Open a repository to report issues, add reproduction steps, and verify or reopen fixes.</p>}
    {project.myRole === "DEVELOPER" && <p className="text-sm text-slate-600">Open an issue assigned to you to analyze it, review the plan, and run the AI implementation.</p>}
    <section className="space-y-3"><h2 className="font-semibold">Repositories</h2>
      {repos.map((repo) => <div key={repo.id} className="flex justify-between items-center gap-3 bg-white border rounded-lg p-4">
        <Link to={`/repos/${repo.id}`} className="font-medium hover:underline">{repo.githubOwner}/{repo.githubRepo} →</Link>
        {project.workspaceAdmin && <button disabled={busy} onClick={() => action(async () => { await api.connectRepo(repo.githubOwner, repo.githubRepo, Number(id)); setMessage("Repository token re-synced."); })} className="text-xs text-slate-500 underline disabled:opacity-50">Re-sync token</button>}
      </div>)}
      {repos.length === 0 && <p className="text-sm text-slate-500">{project.workspaceAdmin ? "Connect a GitHub repository below." : "A workspace admin needs to connect a repository."}</p>}
    </section>
    {project.workspaceAdmin && <>
      <section className="bg-white border rounded-lg p-4 space-y-3"><h2 className="font-semibold">GitHub connection</h2>
        <p className="text-sm text-slate-500">{github?.connected ? `Your GitHub account: ${github.githubLogin}` : "Connect your GitHub account to add repositories to this project."}</p>
        <form className="flex gap-3" onSubmit={(event) => { event.preventDefault(); action(async () => { await api.connectGithubAccount(token.trim()); setToken(""); setMessage("GitHub token saved. Re-sync existing repositories to use the replacement token."); }); }}>
          <input aria-label="GitHub personal access token" type="password" autoComplete="off" className="flex-1 border rounded px-3 py-2 text-sm" placeholder={github?.connected ? "Replacement GitHub token" : "GitHub personal access token"} value={token} onChange={(e) => setToken(e.target.value)} required />
          <button disabled={busy} className="bg-slate-900 text-white rounded px-3 py-2 text-sm disabled:opacity-50">Save token</button>
        </form>
        {github?.connected && <>
          {available.length > 0 && <select aria-label="Available GitHub repositories" className="w-full border rounded px-3 py-2 text-sm" defaultValue="" onChange={(event) => {
            const [owner, repo] = event.target.value.split("/"); if (owner && repo) setConnection({ owner, repo });
          }}><option value="">Choose a repository, or enter owner/name below</option>{available.map((item) => <option key={item.full_name} value={item.full_name}>{item.full_name}</option>)}</select>}
          <form className="flex flex-wrap gap-3" onSubmit={(event) => { event.preventDefault(); action(async () => { await api.connectRepo(connection.owner.trim(), connection.repo.trim(), Number(id)); setConnection({ owner: "", repo: "" }); setMessage("Repository connected."); }); }}>
            <input aria-label="GitHub repository owner" className="border rounded px-3 py-2 text-sm" placeholder="GitHub owner" required value={connection.owner} onChange={(e) => setConnection({ ...connection, owner: e.target.value })} />
            <input aria-label="GitHub repository name" className="border rounded px-3 py-2 text-sm" placeholder="Repository name" required value={connection.repo} onChange={(e) => setConnection({ ...connection, repo: e.target.value })} />
            <button disabled={busy} className="bg-slate-900 text-white rounded px-3 py-2 text-sm disabled:opacity-50">Connect repository</button>
          </form>
        </>}
      </section>
    </>}
    {canManageTeam && <>
      <section className="bg-white border rounded-lg p-4 space-y-3"><h2 className="font-semibold">Add or update a project member</h2>
        {!project.workspaceAdmin && <p className="text-xs text-slate-500">Manage QA members and developers in this project. Workspace admins appoint managers.</p>}
        <form className="flex flex-wrap gap-3" onSubmit={(event) => { event.preventDefault(); action(async () => { await api.setProjectRole(id, membership.userId, membership.role); setMessage("Project role updated."); }); }}>
          <select aria-label="Workspace member" className="border rounded px-3 py-2 text-sm" required value={membership.userId} onChange={(e) => setMembership({ ...membership, userId: e.target.value })}>
            <option value="">Choose company member</option>{workspaceMembers.map((member) => <option key={member.userId} value={member.userId}>{member.username}</option>)}
          </select>
          <select aria-label="Project role" className="border rounded px-3 py-2 text-sm" value={membership.role} onChange={(e) => setMembership({ ...membership, role: e.target.value })}>
            {teamRoles.map((role) => <option key={role} value={role}>{roleLabel(role)}</option>)}
          </select><button disabled={busy} className="bg-slate-900 text-white rounded px-3 py-2 text-sm disabled:opacity-50">Assign role</button>
        </form>
      </section>
      <section className="bg-white border rounded-lg p-4 space-y-3"><h2 className="font-semibold">Invite to this project</h2>
        <form className="space-y-3" onSubmit={(event) => { event.preventDefault(); action(async () => {
          const created = await api.inviteMember(project.workspaceId, { ...invite, email: invite.email.trim(), projectId: Number(id) });
          setInvitationUrl(created.invitationUrl); setInvite({ ...invite, email: "" });
          setMessage(created.emailSent ? "Invitation email sent." : "Invitation created. Email delivery was not confirmed; copy the private link for its intended recipient.");
        }); }}>
          <div className="flex flex-wrap gap-3"><input aria-label="Invite email" type="email" required className="flex-1 border rounded px-3 py-2 text-sm" placeholder="Member’s email" value={invite.email} onChange={(e) => setInvite({ ...invite, email: e.target.value })} />
            <select aria-label="Invitation role" className="border rounded px-3 py-2 text-sm" value={invite.role} onChange={(e) => setInvite({ ...invite, role: e.target.value })}>{teamRoles.map((role) => <option key={role} value={role}>{roleLabel(role)}</option>)}</select></div>
          <label className="flex gap-2 text-sm"><input type="checkbox" checked={invite.sendEmail} onChange={(e) => setInvite({ ...invite, sendEmail: e.target.checked })} />Send invitation email</label>
          <button disabled={busy} className="bg-slate-900 text-white rounded px-3 py-2 text-sm disabled:opacity-50">Invite member</button>
        </form>
        {invitationUrl && <div className="space-y-2"><label className="block text-sm">Private invitation link<input aria-label="Private invitation link" className="block w-full border rounded px-3 py-2 mt-1 text-sm" readOnly value={invitationUrl} /></label>
          <button className="text-sm underline" onClick={async () => { try { await navigator.clipboard.writeText(invitationUrl); setMessage("Invitation link copied."); } catch { setError("Select the private link and copy it manually."); } }}>Copy invitation link</button></div>}
        {invitations.map((item) => <div key={item.id} className="border-t pt-3 text-sm flex justify-between gap-3"><p>{item.email} · {roleLabel(item.role)}<span className="block text-xs text-slate-500">{item.accepted ? "Accepted" : item.revoked ? "Revoked" : `Expires ${new Date(item.expiresAt).toLocaleString()}`}</span></p>
          {!item.accepted && !item.revoked && <button disabled={busy} className="text-red-600 underline" onClick={() => action(() => api.revokeInvitation(project.workspaceId, item.id))}>Revoke</button>}</div>)}
      </section>
    </>}
    <section className="bg-white border rounded-lg p-4 space-y-3"><h2 className="font-semibold">Project team</h2>
      {members.map((member) => <div key={member.userId} className="flex justify-between gap-3 text-sm border-t pt-3"><p>{member.username} <span className="text-slate-500">· {roleLabel(member.role)}</span></p>
        {canManageTeam && (project.workspaceAdmin || !member.admin && ["QA", "DEVELOPER"].includes(member.role)) && <button disabled={busy} className="text-red-600 underline" onClick={() => action(() => api.removeProjectMember(id, member.userId))}>Remove from project</button>}
      </div>)}{members.length === 0 && <p className="text-sm text-slate-500">No project members yet. Invite a manager, QA, and developers to start working together.</p>}
    </section>
  </div>;
}
