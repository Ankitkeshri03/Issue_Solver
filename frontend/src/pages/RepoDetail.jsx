import React, { useEffect, useState } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import { api } from "../api/client.js";
import StatusBadge from "../components/StatusBadge.jsx";
import { buildIssueRows } from "../lib/repoIssues.js";

export default function RepoDetail() {
  const { id } = useParams();
  const navigate = useNavigate();
  const [repo, setRepo] = useState(null);
  const [issues, setIssues] = useState([]);
  const [tickets, setTickets] = useState([]);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const [loading, setLoading] = useState(true);
  const [tab, setTab] = useState("active");
  const [reporting, setReporting] = useState(null);
  const [report, setReport] = useState({ reproductionSteps: "", expectedBehavior: "", actualBehavior: "", severity: "MEDIUM" });

  useEffect(() => {
    load();
  }, [id]);

  async function load() {
    setError("");
    setLoading(true);
    try {
      const repos = await api.listRepos();
      const found = repos.find((r) => String(r.id) === id);
      if (!found) {
        setError("Repo not found or not connected.");
        return;
      }
      setRepo(found);

        try {
          setIssues(await api.listGithubIssues(found.id));
        } catch (err) {
          setIssues([]);
          setError(err.message);
        }
      // The GitHub request synchronizes issue/PR identity and resolved states first.
      setTickets(await api.listTickets({ repoId: found.id }));
    } catch (err) {
      setError(err.message);
    } finally {
      setLoading(false);
    }
  }

  async function handleCreateTicket(issue) {
    setError("");
    setBusy(true);
    try {
      const ticket = await api.createTicket({
        repoId: repo.id,
        githubIssueNumber: issue.number,
        title: issue.title,
        description: issue.body,
        ...report,
      });
      setTickets((prev) => [...prev, ticket]);
      setReporting(null); setReport({ reproductionSteps: "", expectedBehavior: "", actualBehavior: "", severity: "MEDIUM" });
    } catch (err) {
      setError(err.message);
    } finally {
      setBusy(false);
    }
  }

  if (!repo && !error) return <p className="text-sm text-slate-400">Loading…</p>;

  const rows = buildIssueRows(issues, tickets);
  const active = rows.filter((row) => !row.solved);
  const solved = rows.filter((row) => row.solved);
  const visible = tab === "active" ? active : solved;

  return (
    <div className="space-y-8">
      <button onClick={() => navigate(repo?.projectId ? `/projects/${repo.projectId}` : "/")} className="text-sm text-slate-500 hover:underline">
        ← Back to project
      </button>

      {error && <p className="text-red-600 text-sm">{error}</p>}

      {repo && (
        <div>
          <p className="text-xs text-slate-400">Connected repository</p>
          <h1 className="text-xl font-semibold">
            {repo.githubOwner}/{repo.githubRepo}
          </h1>
        </div>
      )}

      {repo && (
        <section>
          <div className="flex items-center justify-between gap-3 mb-3">
            <h2 className="font-semibold text-slate-700">Issues</h2>
            <button onClick={load} disabled={loading || busy} className="text-sm text-slate-500 hover:underline disabled:opacity-50">
              {loading ? "Refreshing…" : "Refresh"}
            </button>
          </div>
          <div role="tablist" aria-label="Issue status" className="flex gap-2 mb-4">
            {[["active", "Active", active.length], ["solved", "Solved", solved.length]].map(([value, label, count]) => (
              <button key={value} role="tab" aria-selected={tab === value} onClick={() => setTab(value)}
                className={`text-sm rounded-full px-4 py-1.5 ${tab === value ? "bg-slate-900 text-white" : "bg-white border text-slate-600"}`}>
                {label} ({count})
              </button>
            ))}
          </div>
          <p className="text-xs text-slate-500 mb-4">Merged fixes and closed GitHub issues await QA verification. Only QA-verified issues are marked solved.</p>
          <div className="grid gap-3">
            {visible.map((row) => (
              <article key={row.number} className="bg-white border rounded-lg p-4">
                <div className="flex items-start justify-between gap-4">
                  <div>
                    <p className="text-xs text-slate-400 mb-1">Issue #{row.number}</p>
                    <h3 className="font-medium text-slate-800">
                      {row.ticket ? <Link className="hover:underline" to={`/tickets/${row.ticket.id}`}>{row.title}</Link> : row.title}
                    </h3>
                    {row.ticket?.assignedToUsername && <p className="text-xs text-slate-500 mt-1">Assigned to {row.ticket.assignedToUsername}</p>}
                    {row.ticket && <p className="text-xs text-slate-500 mt-1">Priority: {row.ticket.priority} · Severity: {row.ticket.severity}</p>}
                  </div>
                  <StatusBadge status={row.solved ? "RESOLVED" : row.ticket?.status || (row.issue?.state === "closed" ? "AWAITING_QA" : "OPEN")} />
                </div>
                <div className="flex gap-4 mt-3 text-sm">
                  {row.ticket ? (
                    <Link to={`/tickets/${row.ticket.id}`} className="text-slate-600 hover:underline">View issue →</Link>
                  ) : repo.myRole === "QA" && !row.solved && (
                    <button disabled={busy} onClick={() => setReporting(row.issue)}
                      className="bg-slate-900 text-white text-xs rounded px-3 py-1.5 disabled:opacity-50">{row.issue?.state === "closed" ? "Import for QA verification" : "Report issue"}</button>
                  )}
                  {row.issue?.html_url && <a href={row.issue.html_url} target="_blank" rel="noreferrer" className="text-slate-500 hover:underline">View on GitHub</a>}
                </div>
              </article>
            ))}
            {loading && <p className="text-sm text-slate-400">Loading issues…</p>}
            {!loading && visible.length === 0 && <p className="text-sm text-slate-400">No {tab} issues.</p>}
          </div>
        </section>
      )}
      {reporting && <section className="bg-white border rounded-lg p-4 space-y-3">
        <h2 className="font-semibold">QA report · #{reporting.number} {reporting.title}</h2>
        <form onSubmit={(event) => { event.preventDefault(); handleCreateTicket(reporting); }} className="space-y-3">
          {[["reproductionSteps", "Steps to reproduce"], ["expectedBehavior", "Expected behavior"], ["actualBehavior", "Actual behavior"]].map(([field, label]) => <label key={field} className="block text-sm">{label}
            <textarea required aria-label={label} maxLength={6000} className="block w-full border rounded px-3 py-2 mt-1" value={report[field]} onChange={(e) => setReport({ ...report, [field]: e.target.value })} /></label>)}
          <label className="block text-sm">Severity <select aria-label="Severity" className="border rounded px-3 py-2" value={report.severity} onChange={(e) => setReport({ ...report, severity: e.target.value })}>{["LOW", "MEDIUM", "HIGH", "CRITICAL"].map((value) => <option key={value}>{value}</option>)}</select></label>
          <div className="flex gap-3"><button disabled={busy} className="bg-slate-900 text-white rounded px-3 py-2 text-sm disabled:opacity-50">Save QA report</button><button type="button" onClick={() => setReporting(null)} className="text-sm underline">Cancel</button></div>
        </form>
      </section>}
    </div>
  );
}
