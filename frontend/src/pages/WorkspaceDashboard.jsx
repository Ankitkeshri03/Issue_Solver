import React, { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { api } from "../api/client.js";
import { roleLabel } from "../lib/roles.js";

export default function WorkspaceDashboard() {
  const [workspaces, setWorkspaces] = useState([]);
  const [projects, setProjects] = useState([]);
  const [selected, setSelected] = useState(localStorage.getItem("activeWorkspaceId") || "");
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(true);
  useEffect(() => {
    Promise.all([api.listWorkspaces(), api.listProjects()]).then(([spaces, items]) => {
      setWorkspaces(spaces); setProjects(items);
      setSelected((previous) => spaces.some((space) => String(space.id) === previous) ? previous : String(spaces[0]?.id || ""));
    }).catch((err) => setError(err.message)).finally(() => setLoading(false));
  }, []);
  const workspace = workspaces.find((space) => String(space.id) === selected);
  return <div className="space-y-6">
    <div><h1 className="text-2xl font-semibold">Your workspaces</h1><p className="text-sm text-slate-500 mt-1">Choose a project to report, manage, or resolve issues with your team.</p></div>
    {error && <p role="alert" className="text-sm text-red-600">{error}</p>}
    {loading ? <p>Loading workspaces…</p> : workspace ? <>
      <div className="flex flex-wrap items-center gap-3">
        <select aria-label="Workspace" className="border rounded px-3 py-2 text-sm" value={selected} onChange={(event) => {
          setSelected(event.target.value); localStorage.setItem("activeWorkspaceId", event.target.value);
        }}>{workspaces.map((space) => <option key={space.id} value={space.id}>{space.name}</option>)}</select>
        {workspace.admin && <><span className="text-xs bg-indigo-50 text-indigo-700 px-2 py-1 rounded">Workspace Admin</span>
          <Link className="text-sm underline" to={`/workspaces/${workspace.id}`}>Manage workspace &amp; team</Link></>}
      </div>
      <div className="grid gap-3">{projects.filter((project) => String(project.workspaceId) === selected).map((project) =>
        <Link key={project.id} to={`/projects/${project.id}`} className="block border bg-white rounded-lg p-4 hover:shadow-sm">
          <div className="flex justify-between gap-3"><h2 className="font-semibold">{project.name}</h2><span className="text-xs text-slate-500">{roleLabel(project.myRole)}</span></div>
          {project.description && <p className="text-sm text-slate-500 mt-1">{project.description}</p>}
          <p className="text-sm text-slate-500 mt-3">Open project →</p>
        </Link>)}
        {!projects.some((project) => String(project.workspaceId) === selected) && <p className="text-sm text-slate-500">{workspace.admin ? "Create a project in workspace management to get started." : "Your admin or project manager needs to add you to a project."}</p>}
      </div>
    </> : !loading && <p className="text-sm text-slate-500">No company access yet. Open your email invitation, or ask your company admin or project manager to invite you.</p>}
  </div>;
}
