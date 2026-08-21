import React, { useEffect, useState } from "react";
import { api } from "../api/client.js";
import { useAuth } from "../context/AuthContext.jsx";
import TicketCard from "../components/TicketCard.jsx";

export default function Dashboard() {
  const { user } = useAuth();
  const [repos, setRepos] = useState([]);
  const [selectedRepo, setSelectedRepo] = useState(null);
  const [connectForm, setConnectForm] = useState({ owner: "", repo: "" });
  const [issues, setIssues] = useState([]);
  const [tickets, setTickets] = useState([]);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    refreshRepos();
    refreshTickets();
  }, []);

  useEffect(() => {
    if (selectedRepo) loadIssues(selectedRepo);
  }, [selectedRepo]);

  async function refreshRepos() {
    try {
      const data = await api.listRepos();
      setRepos(data);
      if (data.length && !selectedRepo) setSelectedRepo(data[0]);
    } catch (err) {
      setError(err.message);
    }
  }

  async function refreshTickets() {
    try {
      setTickets(await api.listTickets());
    } catch (err) {
      setError(err.message);
    }
  }

  async function loadIssues(repo) {
    try {
      setIssues(await api.listGithubIssues(repo.githubOwner, repo.githubRepo));
    } catch (err) {
      setIssues([]);
      setError(err.message);
    }
  }

  async function handleConnect(e) {
    e.preventDefault();
    setError("");
    setBusy(true);
    try {
      const repo = await api.connectRepo(connectForm.owner, connectForm.repo);
      setConnectForm({ owner: "", repo: "" });
      await refreshRepos();
      setSelectedRepo(repo);
    } catch (err) {
      setError(err.message);
    } finally {
      setBusy(false);
    }
  }

  async function handleCreateTicket(issue) {
    setError("");
    try {
      await api.createTicket({
        repoId: selectedRepo.id,
        githubIssueNumber: issue.number,
        title: issue.title,
        description: issue.body,
      });
      await refreshTickets();
    } catch (err) {
      setError(err.message);
    }
  }

  return (
    <div className="space-y-8">
      {error && <p className="text-red-600 text-sm">{error}</p>}

      <section>
        <h2 className="font-semibold text-slate-700 mb-2">Repository</h2>
        <div className="flex flex-wrap items-center gap-2 mb-3">
          {repos.map((r) => (
            <button
              key={r.id}
              onClick={() => setSelectedRepo(r)}
              className={`text-sm px-3 py-1 rounded-full border ${
                selectedRepo?.id === r.id ? "bg-slate-900 text-white border-slate-900" : "bg-white text-slate-600"
              }`}
            >
              {r.githubOwner}/{r.githubRepo}
            </button>
          ))}
        </div>
        {user?.role === "QA" && (
          <form onSubmit={handleConnect} className="flex gap-2">
            <input
              className="border rounded px-3 py-1.5 text-sm"
              placeholder="owner"
              value={connectForm.owner}
              onChange={(e) => setConnectForm({ ...connectForm, owner: e.target.value })}
              required
            />
            <input
              className="border rounded px-3 py-1.5 text-sm"
              placeholder="repo"
              value={connectForm.repo}
              onChange={(e) => setConnectForm({ ...connectForm, repo: e.target.value })}
              required
            />
            <button disabled={busy} className="bg-slate-900 text-white text-sm px-3 py-1.5 rounded disabled:opacity-50">
              Connect
            </button>
          </form>
        )}
      </section>

      {selectedRepo && user?.role === "QA" && (
        <section>
          <h2 className="font-semibold text-slate-700 mb-2">
            Open GitHub issues — {selectedRepo.githubOwner}/{selectedRepo.githubRepo}
          </h2>
          {issues.length === 0 && (
            <p className="text-sm text-slate-400">
              No open issues found (or GITHUB_TOKEN not configured on the backend).
            </p>
          )}
          <div className="space-y-2">
            {issues.map((issue) => (
              <div key={issue.number} className="flex items-center justify-between bg-white border rounded px-3 py-2">
                <span className="text-sm">
                  #{issue.number} {issue.title}
                </span>
                <button
                  onClick={() => handleCreateTicket(issue)}
                  className="text-xs bg-slate-100 hover:bg-slate-200 px-2 py-1 rounded"
                >
                  Create ticket
                </button>
              </div>
            ))}
          </div>
        </section>
      )}

      <section>
        <h2 className="font-semibold text-slate-700 mb-2">Tickets</h2>
        <div className="grid gap-3">
          {tickets.map((t) => (
            <TicketCard key={t.id} ticket={t} />
          ))}
          {tickets.length === 0 && <p className="text-sm text-slate-400">No tickets yet.</p>}
        </div>
      </section>
    </div>
  );
}
