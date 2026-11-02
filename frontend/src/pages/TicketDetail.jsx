import React, { useEffect, useState } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import { api } from "../api/client.js";
import StatusBadge from "../components/StatusBadge.jsx";

export default function TicketDetail() {
  const { id } = useParams();
  const navigate = useNavigate();
  const [ticket, setTicket] = useState(null);
  const [developers, setDevelopers] = useState([]);
  const [failureReason, setFailureReason] = useState("");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const [notes, setNotes] = useState("");
  const [activity, setActivity] = useState([]);
  const [report, setReport] = useState({ reproductionSteps: "", expectedBehavior: "", actualBehavior: "", severity: "MEDIUM" });

  useEffect(() => {
    load();
  }, [id]);

  async function load() {
    try {
      const t = await api.getTicket(id);
      setTicket(t);
      setReport({ reproductionSteps: t.reproductionSteps || "", expectedBehavior: t.expectedBehavior || "", actualBehavior: t.actualBehavior || "", severity: t.severity || "MEDIUM" });
      if (t.permissions?.canAssign) setDevelopers(await api.listDevelopers(t.projectId));
      setActivity(await api.ticketActivity(id));
      // Surface why it failed, so "Retry" isn't a blind guess. The step timeline is the
      // only place the underlying error (quota, push 403, compile error) is recorded.
      if (t.status === "FAILED") {
        const steps = await api.getSteps(id);
        const lastFailed = [...steps].reverse().find((st) => st.status === "FAILED");
        setFailureReason(lastFailed ? `${lastFailed.stepName}: ${lastFailed.message}` : "");
      } else {
        setFailureReason("");
      }
    } catch (err) {
      setError(err.message);
    }
  }

  async function handleAssign(e) {
    const developerId = e.target.value;
    setBusy(true); setError("");
    try {
      setTicket(await (developerId ? api.assignTicket(id, Number(developerId)) : api.unassignTicket(id)));
    } catch (err) {
      setError(err.message);
    } finally { setBusy(false); }
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

  const isAssignedDeveloper = ticket.permissions?.canDevelop;

  async function update(fn) {
    setBusy(true); setError("");
    try { setTicket(await fn()); setActivity(await api.ticketActivity(id)); setNotes(""); }
    catch (err) { setError(err.message); } finally { setBusy(false); }
  }

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

      {ticket.permissions?.canAssign && (
        <section className="bg-white border rounded-lg p-4">
          <h2 className="text-sm font-semibold text-slate-600 mb-2">Assign to developer</h2>
          <select
            className="border rounded px-3 py-1.5 text-sm"
            value={ticket.assignedToId || ""}
            onChange={handleAssign}
            disabled={busy}
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

      <section className="bg-white border rounded-lg p-4 space-y-3">
        <h2 className="text-sm font-semibold text-slate-600">Priority and severity</h2>
        <div className="flex gap-5 text-sm"><label>Priority {ticket.permissions?.canPrioritize ? <select aria-label="Issue priority" disabled={busy} className="border rounded px-2 py-1 ml-2" value={ticket.priority} onChange={(e) => update(() => api.setPriority(id, e.target.value))}>{["LOW", "MEDIUM", "HIGH", "CRITICAL"].map((value) => <option key={value}>{value}</option>)}</select> : ticket.priority}</label><p>Severity: {ticket.severity}</p></div>
      </section>

      <section className="bg-white border rounded-lg p-4 space-y-3">
        <h2 className="text-sm font-semibold text-slate-600">QA bug report</h2>
        {ticket.permissions?.canReport ? <form onSubmit={(event) => { event.preventDefault(); update(() => api.updateReport(id, report)); }} className="space-y-3">
          {[["reproductionSteps", "Steps to reproduce"], ["expectedBehavior", "Expected behavior"], ["actualBehavior", "Actual behavior"]].map(([field, label]) => <label key={field} className="block text-sm">{label}<textarea aria-label={label} required maxLength={6000} className="block w-full border rounded px-3 py-2 mt-1" value={report[field]} onChange={(e) => setReport({ ...report, [field]: e.target.value })} /></label>)}
          <select aria-label="Bug severity" className="border rounded px-3 py-2 text-sm" value={report.severity} onChange={(e) => setReport({ ...report, severity: e.target.value })}>{["LOW", "MEDIUM", "HIGH", "CRITICAL"].map((value) => <option key={value}>{value}</option>)}</select>
          <button disabled={busy} className="block bg-slate-900 text-white text-sm rounded px-3 py-2 disabled:opacity-50">Save bug report</button>
        </form> : <div className="space-y-3 text-sm">{[["reproductionSteps", "Steps to reproduce"], ["expectedBehavior", "Expected behavior"], ["actualBehavior", "Actual behavior"]].map(([field, label]) => <div key={field}><h3 className="font-medium">{label}</h3><p className="whitespace-pre-wrap text-slate-600">{ticket[field] || "Not provided yet."}</p></div>)}</div>}
      </section>

      {(ticket.permissions?.canVerify || ticket.permissions?.canReopen) && <section className="bg-white border rounded-lg p-4 space-y-3">
        <h2 className="text-sm font-semibold text-slate-600">QA verification</h2><p className="text-sm text-slate-500">Retest the reported behavior and record your findings before marking the issue solved.</p>
        <form onSubmit={(event) => { event.preventDefault(); update(() => api.verifyTicket(id, notes)); }} className="space-y-3">
          <textarea aria-label="QA verification notes" required maxLength={6000} placeholder="What did you retest? What was the result?" className="w-full border rounded px-3 py-2 text-sm" value={notes} onChange={(e) => setNotes(e.target.value)} />
          <div className="flex gap-3">{ticket.permissions.canVerify && <button disabled={busy || !notes.trim()} className="bg-green-700 text-white text-sm rounded px-3 py-2 disabled:opacity-50">Verify &amp; mark solved</button>}
            {ticket.permissions.canReopen && <button type="button" disabled={busy || !notes.trim()} onClick={() => update(() => api.reopenTicket(id, notes))} className="bg-amber-600 text-white text-sm rounded px-3 py-2 disabled:opacity-50">Reopen for developer</button>}</div>
        </form>
      </section>}
      {ticket.qaVerifiedAt && <p className="text-sm text-green-700">QA verified on {new Date(ticket.qaVerifiedAt).toLocaleString()}: {ticket.qaNotes}</p>}

      <div className="flex flex-wrap gap-4 text-sm"><Link className="text-slate-500 underline" to={`/tickets/${id}/diff`}>View diff &amp; PR</Link><Link className="text-slate-500 underline" to={`/tickets/${id}/live`}>View agent progress</Link><Link className="text-slate-500 underline" to={`/repos/${ticket.repoId}`}>Back to issues</Link></div>

      {ticket.status === "FAILED" && failureReason && (
        <section className="bg-red-50 border border-red-200 rounded-lg p-4">
          <h2 className="text-sm font-semibold text-red-700 mb-2">Why it failed</h2>
          <pre className="text-xs whitespace-pre-wrap text-red-900 max-h-48 overflow-y-auto">
            {failureReason}
          </pre>
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
          {/* A FAILED ticket was previously a dead end -- the only way back was a manual
              API call. The agent endpoints are idempotent w.r.t. status, so retrying is
              just re-invoking them. Which retry makes sense depends on how far it got:
              no plan means analysis failed; a plan means coding/testing/push failed. */}
          {ticket.status === "FAILED" && !ticket.plan && (
            <button
              disabled={busy}
              onClick={handleAnalyze}
              className="bg-amber-600 text-white text-sm px-4 py-2 rounded disabled:opacity-50"
            >
              Retry analysis
            </button>
          )}
          {ticket.status === "FAILED" && ticket.plan && (
            <>
              <button
                disabled={busy}
                onClick={handleApprove}
                className="bg-amber-600 text-white text-sm px-4 py-2 rounded disabled:opacity-50"
              >
                Retry implementation
              </button>
              <button
                disabled={busy}
                onClick={handleAnalyze}
                className="bg-white border text-sm px-4 py-2 rounded disabled:opacity-50"
              >
                Re-analyze from scratch
              </button>
            </>
          )}
        </section>
      )}
      {activity.length > 0 && <section className="bg-white border rounded-lg p-4 space-y-3"><h2 className="text-sm font-semibold text-slate-600">Issue activity</h2>{activity.map((item) => <div key={item.id} className="border-t pt-2 text-sm"><p className="text-slate-500 text-xs">{item.actorUsername} · {new Date(item.createdAt).toLocaleString()}</p><p className="whitespace-pre-wrap">{item.message}</p></div>)}</section>}
    </div>
  );
}
