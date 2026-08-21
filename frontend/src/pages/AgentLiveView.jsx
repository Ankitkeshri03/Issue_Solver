import React, { useEffect, useRef, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { api } from "../api/client.js";

const ICON = { RUNNING: "🔄", DONE: "✅", FAILED: "❌" };

export default function AgentLiveView() {
  const { id } = useParams();
  const [steps, setSteps] = useState([]);
  const [connected, setConnected] = useState(false);
  const bottomRef = useRef(null);

  useEffect(() => {
    api.getSteps(id).then(setSteps).catch(() => {});

    const source = new EventSource(api.streamUrl(id));
    source.addEventListener("open", () => setConnected(true));
    source.addEventListener("agent-step", (event) => {
      const step = JSON.parse(event.data);
      setSteps((prev) => [...prev.filter((s) => s.id !== step.id), step]);
    });
    source.onerror = () => {
      setConnected(false);
      source.close();
    };

    return () => source.close();
  }, [id]);

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: "smooth" });
  }, [steps]);

  const terminal = steps.some((s) => ["PR_CREATED", "TESTING", "planning"].includes(s.stepName) && s.status !== "RUNNING");

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <h1 className="text-lg font-semibold">Agent live progress</h1>
        <span className={`text-xs px-2 py-1 rounded-full ${connected ? "bg-emerald-100 text-emerald-700" : "bg-slate-100 text-slate-500"}`}>
          {connected ? "live" : "disconnected"}
        </span>
      </div>

      <div className="bg-white border rounded-lg divide-y">
        {steps
          .filter((s) => s.stepName !== "diff")
          .map((step) => (
            <div key={step.id} className="p-3 flex gap-3">
              <span>{ICON[step.status] || "•"}</span>
              <div>
                <p className="text-sm font-medium text-slate-800">{step.stepName}</p>
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
