import React, { useEffect, useState } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import { api } from "../api/client.js";
import { useAuth } from "../context/AuthContext.jsx";
import TicketCard from "../components/TicketCard.jsx";

export default function RepoDetail() {
  const { id } = useParams();
  const { user } = useAuth();
  const navigate = useNavigate();
  const [repo, setRepo] = useState(null);
  const [issues, setIssues] = useState([]);
  const [tickets, setTickets] = useState([]);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    load();
  }, [id]);

  async function load() {
    setError("");
    try {
      const repos = await api.listRepos();
      const found = repos.find((r) => String(r.id) === id);
      if (!found) {
        setError("Repo not found or not connected.");
        return;
      }
      setRepo(found);

      const repoTickets = await api.listTickets({ repoId: found.id });
      setTickets(repoTickets);

      if (user?.role === "QA") {
        try {
          setIssues(await api.listGithubIssues(found.githubOwner, found.githubRepo));
        } catch (err) {
          setIssues([]);
          setError(err.message);
        }
      }
    } catch (err) {
      setError(err.message);
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
      });
      setTickets((prev) => [...prev, ticket]);
    } catch (err) {
      setError(err.message);
    } finally {
      setBusy(false);
    }
  }

  if (!repo && !error) return <p className="text-sm text-slate-400">Loading…</p>;

  const ticketedIssueNumbers = new Set(tickets.map((t) => t.githubIssueNumber));

  return (
    <div className="space-y-8">
      <button onClick={() => navigate("/")} className="text-sm text-slate-500 hover:underline">
        ← Back to repos
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

      {repo && user?.role === "QA" && (
        <section>
          <h2 className="font-semibold text-slate-700 mb-2">Open GitHub issues</h2>
          {issues.length === 0 && (
            <p className="text-sm text-slate-400">
              No open issues found (or this repo's connecting account has no valid GitHub token).
            </p>
          )}
          <div className="space-y-2">
            {issues.map((issue) => {
              const hasTicket = ticketedIssueNumbers.has(issue.number);
              return (
                <div key={issue.number} className="flex items-center justify-between bg-white border rounded px-3 py-2">
                  <span className="text-sm">
                    #{issue.number} {issue.title}
                  </span>
                  <button
                    disabled={busy || hasTicket}
                    onClick={() => handleCreateTicket(issue)}
                    className="text-xs bg-slate-100 hover:bg-slate-200 px-2 py-1 rounded disabled:opacity-50"
                  >
                    {hasTicket ? "Ticket created" : "Create ticket"}
                  </button>
                </div>
              );
            })}
          </div>
        </section>
      )}

      {repo && (
        <section>
          <h2 className="font-semibold text-slate-700 mb-2">Tickets for this repo</h2>
          <div className="grid gap-3">
            {tickets.map((t) => (
              <TicketCard key={t.id} ticket={t} />
            ))}
            {tickets.length === 0 && <p className="text-sm text-slate-400">No tickets for this repo yet.</p>}
          </div>
        </section>
      )}
    </div>
  );
}
