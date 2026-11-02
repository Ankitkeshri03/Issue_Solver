import React, { useEffect, useRef, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { api } from "../api/client.js";
import { mergeSteps, progressStatus, watchAgentProgress } from "../lib/agentProgress.js";

const ICON = { RUNNING: "🔄", DONE: "✅", FAILED: "❌", STOPPED: "⏹️" };

export default function AgentLiveView() {
  const { id } = useParams();
  const [steps, setSteps] = useState([]);
  const [connection, setConnection] = useState("connecting");
  const [ticket, setTicket] = useState(null);
  const [error, setError] = useState("");
  const bottomRef = useRef(null);

  useEffect(() => {
    setSteps([]);
    setTicket(null);
    setError("");
    return watchAgentProgress(id, {
      api,
      onSnapshot: (saved, current) => {
        setSteps((previous) => mergeSteps(previous, saved));
        setTicket(current);
      },
      onStep: (step) => setSteps((previous) => mergeSteps(previous, [step])),
      onState: setConnection,
      onError: setError,
    });
  }, [id]);

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: "smooth" });
  }, [steps]);

  const labels = {
    connecting: "connecting…", live: "live", reconnecting: "reconnecting…",
    complete: ticket?.status === "PLAN_READY" ? "analysis complete" : "complete",
    failed: "failed", idle: "waiting for analysis", unauthorized: "sign-in required",
  };
  // Show the latest state of each phase rather than an old RUNNING row beside DONE.
  const latest = [...new Map(steps.map((step) => [step.stepName, step])).values()];

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <h1 className="text-lg font-semibold">Agent live progress</h1>
        <span role="status" className={`text-xs px-2 py-1 rounded-full ${["live", "complete"].includes(connection) ? "bg-emerald-100 text-emerald-700" : connection === "failed" ? "bg-red-100 text-red-700" : "bg-slate-100 text-slate-500"}`}>
          {labels[connection]}
        </span>
      </div>

      {error && <p role="alert" className="text-sm text-red-600">{error}</p>}
      {connection === "reconnecting" && <p className="text-sm text-slate-500">Reconnecting to live updates. Progress is also checked automatically.</p>}
      {ticket?.status === "PLAN_READY" && <p className="text-sm text-emerald-700">Analysis is ready. Go back to the ticket to review and approve the plan.</p>}
      {ticket?.status === "FAILED" && <p className="text-sm text-red-600">This run stopped. Go back to the ticket to review the error and retry.</p>}

      <div className="bg-white border rounded-lg divide-y">
        {latest
          .filter((s) => s.stepName !== "diff")
          .map((step) => (
            <div key={step.id} className="p-3 flex gap-3">
              <span>{ICON[progressStatus(step, ticket?.status)] || "•"}</span>
              <div>
                <p className="text-sm font-medium text-slate-800">{step.stepName}{progressStatus(step, ticket?.status) === "STOPPED" ? " · stopped" : ""}</p>
                <p className="text-xs text-slate-500 whitespace-pre-wrap">{step.message}</p>
              </div>
            </div>
          ))}
        {steps.length === 0 && <p className="p-4 text-sm text-slate-400">Waiting for the agent to start…</p>}
        <div ref={bottomRef} />
      </div>

      <div className="flex gap-3">
        <Link to={`/tickets/${id}`} className="text-sm text-slate-500 hover:underline">
          ← Back to ticket
        </Link>
        <Link to={`/tickets/${id}/diff`} className="text-sm text-slate-500 hover:underline">
          View diff &amp; PR →
        </Link>
      </div>
    </div>
  );
}
