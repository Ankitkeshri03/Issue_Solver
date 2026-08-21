import React, { useEffect, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { api } from "../api/client.js";
import { useAuth } from "../context/AuthContext.jsx";
import StatusBadge from "../components/StatusBadge.jsx";

export default function TicketDetail() {
  const { id } = useParams();
  const { user } = useAuth();
  const navigate = useNavigate();
  const [ticket, setTicket] = useState(null);
  const [developers, setDevelopers] = useState([]);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    load();
    if (user?.role === "QA") api.listDevelopers().then(setDevelopers).catch(() => {});
  }, [id]);

  async function load() {
    try {
      setTicket(await api.getTicket(id));
    } catch (err) {
      setError(err.message);
    }
  }

  async function handleAssign(e) {
    const developerId = e.target.value;
    if (!developerId) return;
    try {
      setTicket(await api.assignTicket(id, Number(developerId)));
    } catch (err) {
      setError(err.message);
    }
  }

  async function handleAnalyze() {
    setBusy(true);
    setError("");
    try {
      await api.analyzeTicket(id);
      navigate(`/tickets/${id}/live`);
    } catch (err) {
      setError(err.message);
      setBusy(false);
    }
  }

  async function handleApprove() {
    setBusy(true);
    setError("");
    try {
      await api.approveTicket(id);
      navigate(`/tickets/${id}/live`);
    } catch (err) {
      setError(err.message);
      setBusy(false);
    }
  }

  if (!ticket) return <p className="text-sm text-slate-400">{error || "Loading..."}</p>;

  const isAssignedDeveloper = user?.role === "DEVELOPER" && ticket.assignedToId === user.id;

  return (
    <div className="space-y-6">
      <div>
        <p className="text-xs text-slate-400">
          {ticket.repoFullName} #{ticket.githubIssueNumber}
        </p>
        <div className="flex items-center gap-3 mt-1">
          <h1 className="text-xl font-semibold">{ticket.title}</h1>
          <StatusBadge status={ticket.status} />
        </div>
      </div>

      {error && <p className="text-red-600 text-sm">{error}</p>}

      <section className="bg-white border rounded-lg p-4">
        <h2 className="text-sm font-semibold text-slate-600 mb-2">Issue description</h2>
        <p className="text-sm whitespace-pre-wrap text-slate-700">{ticket.description || "—"}</p>
      </section>

      {user?.role === "QA" && (
        <section className="bg-white border rounded-lg p-4">
          <h2 className="text-sm font-semibold text-slate-600 mb-2">Assign to developer</h2>
          <select
            className="border rounded px-3 py-1.5 text-sm"
            value={ticket.assignedToId || ""}
            onChange={handleAssign}
          >
            <option value="">Unassigned</option>
            {developers.map((d) => (
              <option key={d.id} value={d.id}>
                {d.username}
              </option>
            ))}
          </select>
        </section>
      )}

      {ticket.plan && (
        <section className="bg-white border rounded-lg p-4">
          <h2 className="text-sm font-semibold text-slate-600 mb-2">AI-proposed plan</h2>
          <p className="text-sm whitespace-pre-wrap text-slate-700">{ticket.plan}</p>
        </section>
      )}

      {isAssignedDeveloper && (
        <section className="flex gap-3">
          {ticket.status === "OPEN" && (
            <button
              disabled={busy}
              onClick={handleAnalyze}
              className="bg-slate-900 text-white text-sm px-4 py-2 rounded disabled:opacity-50"
            >
              Analyze with AI
            </button>
          )}
          {ticket.status === "PLAN_READY" && (
            <button
              disabled={busy}
              onClick={handleApprove}
              className="bg-emerald-600 text-white text-sm px-4 py-2 rounded disabled:opacity-50"
            >
              Approve plan &amp; implement
            </button>
          )}
          {["ANALYZING", "IMPLEMENTING", "TESTING"].includes(ticket.status) && (
            <button
              onClick={() => navigate(`/tickets/${id}/live`)}
              className="bg-white border text-sm px-4 py-2 rounded"
            >
              View live progress
            </button>
          )}
          {["PR_CREATED", "RESOLVED", "FAILED"].includes(ticket.status) && (
            <button
              onClick={() => navigate(`/tickets/${id}/diff`)}
              className="bg-white border text-sm px-4 py-2 rounded"
            >
              View diff &amp; PR
            </button>
          )}
        </section>
      )}
    </div>
  );
}
