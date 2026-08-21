import React, { useEffect, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { api } from "../api/client.js";
import StatusBadge from "../components/StatusBadge.jsx";

export default function DiffAndPr() {
  const { id } = useParams();
  const [ticket, setTicket] = useState(null);
  const [diff, setDiff] = useState("");
  const [testOutput, setTestOutput] = useState("");
  const [error, setError] = useState("");

  useEffect(() => {
    load();
  }, [id]);

  async function load() {
    try {
      const [t, steps] = await Promise.all([api.getTicket(id), api.getSteps(id)]);
      setTicket(t);
      const diffStep = [...steps].reverse().find((s) => s.stepName === "diff");
      if (diffStep) setDiff(diffStep.message);
      const testStep = [...steps].reverse().find((s) => s.stepName === "testing");
      if (testStep) setTestOutput(testStep.message);
    } catch (err) {
      setError(err.message);
    }
  }

  if (!ticket) return <p className="text-sm text-slate-400">{error || "Loading..."}</p>;

  return (
    <div className="space-y-6">
      <div className="flex items-center gap-3">
        <h1 className="text-lg font-semibold">{ticket.title}</h1>
        <StatusBadge status={ticket.status} />
      </div>
      {error && <p className="text-red-600 text-sm">{error}</p>}

      <section className="bg-white border rounded-lg p-4">
        <h2 className="text-sm font-semibold text-slate-600 mb-2">Code diff</h2>
        <pre className="text-xs bg-slate-900 text-slate-100 rounded p-3 overflow-x-auto whitespace-pre-wrap">
          {diff || "No diff available yet."}
        </pre>
      </section>

      {testOutput && (
        <section className="bg-white border rounded-lg p-4">
          <h2 className="text-sm font-semibold text-slate-600 mb-2">mvn test output</h2>
          <pre className="text-xs bg-slate-50 border rounded p-3 overflow-x-auto whitespace-pre-wrap">{testOutput}</pre>
        </section>
      )}

      <section className="bg-white border rounded-lg p-4 flex items-center justify-between">
        <div>
          <h2 className="text-sm font-semibold text-slate-600">Pull request</h2>
          <p className="text-xs text-slate-500 mt-1">Branch: {ticket.branchName || "—"}</p>
        </div>
        {ticket.prUrl ? (
          <a
            href={ticket.prUrl}
            target="_blank"
            rel="noreferrer"
            className="bg-slate-900 text-white text-sm px-4 py-2 rounded"
          >
            View PR on GitHub
          </a>
        ) : (
          <span className="text-sm text-slate-400">
            {ticket.status === "FAILED" ? "Tests failed — no PR created" : "Not created yet"}
          </span>
        )}
      </section>

      <Link to={`/tickets/${id}`} className="text-sm text-slate-500 hover:underline">
        ← Back to ticket
      </Link>
    </div>
  );
}
