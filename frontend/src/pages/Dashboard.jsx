import React, { useEffect, useState } from "react";
import { api } from "../api/client.js";
import { useAuth } from "../context/AuthContext.jsx";
import TicketCard from "../components/TicketCard.jsx";

export default function Dashboard() {
  const { user } = useAuth();
  const [githubStatus, setGithubStatus] = useState(null);
  const [githubTokenInput, setGithubTokenInput] = useState("");
  const [availableRepos, setAvailableRepos] = useState([]);
  const [repos, setRepos] = useState([]);
  const [selectedRepo, setSelectedRepo] = useState(null);
  const [connectForm, setConnectForm] = useState({ owner: "", repo: "" });
  const [showManualConnect, setShowManualConnect] = useState(false);
  const [issues, setIssues] = useState([]);
  const [tickets, setTickets] = useState([]);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    refreshGithubStatus();
    refreshRepos();
    refreshTickets();
  }, []);

  useEffect(() => {
    // GitHub issue fetching is QA-only UI (see the "Open GitHub issues" section below) --
    // skip it entirely for other roles so a Developer's Dashboard doesn't silently burn
    // through GitHub's 60-req/hour unauthenticated rate limit on every mount for a feature
    // it can't even see.
    if (selectedRepo && user?.role === "QA") loadIssues(selectedRepo);
  }, [selectedRepo, user?.role]);

  async function refreshGithubStatus() {
    try {
      const status = await api.getGithubStatus();
      setGithubStatus(status);
      if (status.connected) refreshAvailableRepos();
    } catch (err) {
      setError(err.message);
    }
  }

  async function refreshAvailableRepos() {
    try {
      setAvailableRepos(await api.listAvailableGithubRepos());
    } catch (err) {
      setAvailableRepos([]);
      setError(err.message);
    }
  }

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

  async function handleConnectGithub(e) {
    e.preventDefault();
    setError("");
    setBusy(true);
    try {
      const status = await api.connectGithubAccount(githubTokenInput.trim());
      setGithubStatus(status);
      setGithubTokenInput("");
      await refreshAvailableRepos();
    } catch (err) {
      setError(err.message);
    } finally {
      setBusy(false);
    }
  }

  async function handleDisconnectGithub() {
    setError("");
    try {
      await api.disconnectGithubAccount();
      setGithubStatus({ connected: false, githubLogin: null });
      setAvailableRepos([]);
    } catch (err) {
      setError(err.message);
    }
  }

  async function handleConnectFromList(repo) {
    setError("");
    setBusy(true);
    try {
      const [owner, name] = repo.full_name.split("/");
      const connected = await api.connectRepo(owner, name);
      await refreshRepos();
      setSelectedRepo(connected);
    } catch (err) {
      setError(err.message);
    } finally {
      setBusy(false);
    }
  }

  async function handleManualConnect(e) {
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

      {user?.role === "QA" && (
        <section className="bg-white border rounded-lg p-4">
          <h2 className="font-semibold text-slate-700 mb-2">GitHub account</h2>
          {githubStatus === null ? (
            <p className="text-sm text-slate-400">Checking…</p>
          ) : githubStatus.connected ? (
            <div className="flex items-center justify-between">
              <p className="text-sm text-slate-600">
                Connected as <span className="font-medium">{githubStatus.githubLogin}</span> — repos you have
                access to are listed below.
              </p>
              <button onClick={handleDisconnectGithub} className="text-xs text-red-600 hover:underline">
                Disconnect
              </button>
            </div>
          ) : (
            <form onSubmit={handleConnectGithub} className="flex gap-2">
              <input
                className="flex-1 border rounded px-3 py-1.5 text-sm font-mono"
                placeholder="Paste a GitHub personal access token (repo scope)"
                type="password"
                value={githubTokenInput}
                onChange={(e) => setGithubTokenInput(e.target.value)}
                required
              />
              <button disabled={busy} className="bg-slate-900 text-white text-sm px-4 py-1.5 rounded disabled:opacity-50">
                Connect GitHub
              </button>
            </form>
          )}
        </section>
      )}

      {user?.role === "QA" && githubStatus?.connected && (
        <section>
          <h2 className="font-semibold text-slate-700 mb-2">Your GitHub repos</h2>
          {availableRepos.length === 0 && (
            <p className="text-sm text-slate-400">No repos found for this account/token.</p>
          )}
          <div className="grid gap-2">
            {availableRepos.map((r) => {
              const alreadyConnected = repos.some((c) => `${c.githubOwner}/${c.githubRepo}` === r.full_name);
              return (
                <div key={r.full_name} className="flex items-center justify-between bg-white border rounded px-3 py-2">
                  <div>
                    <span className="text-sm font-medium">{r.full_name}</span>
                    {r.language && <span className="text-xs text-slate-400 ml-2">{r.language}</span>}
                    {r.private && <span className="text-xs text-slate-400 ml-2">private</span>}
                  </div>
                  <button
                    disabled={busy || alreadyConnected}
                    onClick={() => handleConnectFromList(r)}
                    className="text-xs bg-slate-100 hover:bg-slate-200 px-2 py-1 rounded disabled:opacity-50"
                  >
                    {alreadyConnected ? "Connected" : "Connect"}
                  </button>
                </div>
              );
            })}
          </div>
          <button
            className="text-xs text-slate-500 mt-2 hover:underline"
            onClick={() => setShowManualConnect(!showManualConnect)}
          >
            {showManualConnect ? "Hide manual entry" : "Repo not listed? Enter owner/repo manually"}
          </button>
          {showManualConnect && (
            <form onSubmit={handleManualConnect} className="flex gap-2 mt-2">
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
      )}

      <section>
        <h2 className="font-semibold text-slate-700 mb-2">Connected repositories</h2>
        <div className="flex flex-wrap items-center gap-2">
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
          {repos.length === 0 && <p className="text-sm text-slate-400">No repos connected yet.</p>}
        </div>
      </section>

      {selectedRepo && user?.role === "QA" && (
        <section>
          <h2 className="font-semibold text-slate-700 mb-2">
            Open GitHub issues — {selectedRepo.githubOwner}/{selectedRepo.githubRepo}
          </h2>
          {issues.length === 0 && (
            <p className="text-sm text-slate-400">
              No open issues found (or this repo's connecting account has no GitHub token configured).
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
