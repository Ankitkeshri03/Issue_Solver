import React, { useEffect, useState } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import { api } from "../api/client.js";
import { ROLE_LABELS, roleLabel } from "../lib/roles.js";

export default function WorkspaceDetail() {
  const { id } = useParams(); const navigate = useNavigate();
  const [workspace, setWorkspace] = useState(null); const [projects, setProjects] = useState([]);
  const [members, setMembers] = useState([]); const [invitations, setInvitations] = useState([]);
  const [project, setProject] = useState({ name: "", description: "" });
  const [invite, setInvite] = useState({ email: "", role: "DEVELOPER", projectId: "", sendEmail: true });
  const [created, setCreated] = useState(null); const [message, setMessage] = useState("");
  const [error, setError] = useState(""); const [busy, setBusy] = useState(false);
  useEffect(() => { load(); }, [id]);
  async function load() {
    try {
      const [space, items] = await Promise.all([api.getWorkspace(id), api.listProjects(id)]);
      setWorkspace(space); setProjects(items);
      setInvite((value) => ({ ...value, projectId: value.projectId || String(items[0]?.id || "") }));
      if (space.admin) {
        const [people, links] = await Promise.all([api.workspaceMembers(id), api.listInvitations(id)]);
        setMembers(people); setInvitations(links);
      }
    } catch (err) { setError(err.message); }
  }
  async function action(fn) {
    setBusy(true); setError(""); setMessage("");
    try { await fn(); await load(); } catch (err) { setError(err.message); } finally { setBusy(false); }
  }
  if (!workspace) return <p>{error || "Loading workspace…"}</p>;
  return <div className="space-y-6">
    <Link className="text-sm text-slate-500 hover:underline" to="/">← Workspaces</Link>
    <div><h1 className="text-2xl font-semibold">{workspace.name}</h1><p className="text-sm text-slate-500">{workspace.admin ? "Workspace Admin · projects, team access, and invitations" : "Workspace projects"}</p></div>
    {error && <p role="alert" className="text-red-600 text-sm">{error}</p>}{message && <p role="status" className="text-green-700 text-sm">{message}</p>}
    <section className="bg-white border rounded-lg p-4 space-y-3"><h2 className="font-semibold">Projects</h2>
      {projects.map((item) => <Link key={item.id} className="block text-sm underline" to={`/projects/${item.id}`}>{item.name} →</Link>)}
      {workspace.admin && <form onSubmit={(event) => { event.preventDefault(); action(async () => {
        const item = await api.createProject(id, project); navigate(`/projects/${item.id}`);
      }); }} className="space-y-2 pt-3 border-t">
        <input aria-label="Project name" className="w-full border rounded px-3 py-2 text-sm" placeholder="New project name" maxLength={150} required value={project.name} onChange={(e) => setProject({ ...project, name: e.target.value })} />
        <textarea aria-label="Project description" className="w-full border rounded px-3 py-2 text-sm" placeholder="Project description (optional)" maxLength={2000} value={project.description} onChange={(e) => setProject({ ...project, description: e.target.value })} />
        <button disabled={busy} className="bg-slate-900 text-white rounded px-3 py-2 text-sm disabled:opacity-50">Create project</button>
      </form>}
    </section>
    {workspace.admin && <>
      <section className="bg-white border rounded-lg p-4 space-y-3"><h2 className="font-semibold">Invite a team member</h2>
        <form onSubmit={(event) => { event.preventDefault(); action(async () => {
          const result = await api.inviteMember(id, { ...invite, projectId: invite.role === "WORKSPACE_ADMIN" ? null : Number(invite.projectId) });
          setCreated(result); setMessage(result.emailSent ? "Invitation email sent." : invite.sendEmail ? "Invitation created. Email could not be sent; copy the private link below." : "Invitation created. Share the private link with the invited person.");
        }); }} className="space-y-3">
          <input aria-label="Invite email" className="w-full border rounded px-3 py-2 text-sm" type="email" placeholder="teammate@company.com" required maxLength={255} value={invite.email} onChange={(e) => setInvite({ ...invite, email: e.target.value })} />
          <div className="flex flex-wrap gap-3"><select aria-label="Invitation role" className="border rounded px-3 py-2 text-sm" value={invite.role} onChange={(e) => setInvite({ ...invite, role: e.target.value })}>
            {Object.entries(ROLE_LABELS).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
          </select>
          {invite.role !== "WORKSPACE_ADMIN" && <select aria-label="Invitation project" className="border rounded px-3 py-2 text-sm" value={invite.projectId} required onChange={(e) => setInvite({ ...invite, projectId: e.target.value })}>
            <option value="">Choose project</option>{projects.map((item) => <option key={item.id} value={item.id}>{item.name}</option>)}
          </select>}</div>
          <label className="flex gap-2 text-sm"><input type="checkbox" checked={invite.sendEmail} onChange={(e) => setInvite({ ...invite, sendEmail: e.target.checked })} />Send the invitation by email</label>
          <button disabled={busy} className="bg-slate-900 text-white rounded px-3 py-2 text-sm disabled:opacity-50">Create invitation</button>
        </form>
        {created && <div className="border rounded bg-slate-50 p-3 space-y-2"><p className="text-xs text-slate-500">Private invitation · expires {new Date(created.expiresAt).toLocaleDateString()}</p>
          <input aria-label="Private invitation link" className="w-full border rounded px-2 py-1 text-sm" readOnly value={created.invitationUrl} />
          <button className="text-sm underline" onClick={async () => { try { await navigator.clipboard.writeText(created.invitationUrl); setMessage("Invitation link copied."); } catch { setMessage("Select the link above and copy it."); } }}>Copy link</button>
        </div>}
      </section>
      <section className="bg-white border rounded-lg p-4 space-y-3"><h2 className="font-semibold">Workspace members</h2>
        {members.map((member) => <div key={member.userId} className="flex flex-wrap justify-between gap-3 border-t pt-3 text-sm"><div><p className="font-medium">{member.username} {member.admin && <span className="text-xs text-indigo-700">· Workspace Admin</span>}</p><p className="text-slate-500">{member.email}</p></div>
          <div className="flex gap-3"><button disabled={busy} className="underline disabled:opacity-50" onClick={() => action(() => api.setWorkspaceAdmin(id, member.userId, !member.admin))}>{member.admin ? "Remove admin role" : "Make workspace admin"}</button>
            <button disabled={busy} className="text-red-600 underline disabled:opacity-50" onClick={() => action(() => api.removeWorkspaceMember(id, member.userId))}>Remove access</button></div>
        </div>)}
      </section>
      <section className="bg-white border rounded-lg p-4 space-y-3"><h2 className="font-semibold">Invitations</h2>
        {invitations.map((item) => <div key={item.id} className="flex justify-between gap-3 border-t pt-3 text-sm"><div>{item.email}<p className="text-xs text-slate-500">{roleLabel(item.role)} · {item.accepted ? "Accepted" : item.revoked ? "Revoked" : new Date(item.expiresAt) < new Date() ? "Expired" : "Pending"}</p></div>
          {!item.accepted && !item.revoked && <button disabled={busy} className="text-red-600 underline" onClick={() => action(() => api.revokeInvitation(id, item.id))}>Revoke</button>}
        </div>)}{invitations.length === 0 && <p className="text-sm text-slate-500">No invitations yet.</p>}
      </section>
    </>}
  </div>;
}
