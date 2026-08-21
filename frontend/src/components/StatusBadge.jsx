import React from "react";

const COLORS = {
  OPEN: "bg-slate-200 text-slate-700",
  ANALYZING: "bg-blue-100 text-blue-700",
  PLAN_READY: "bg-amber-100 text-amber-700",
  PLAN_APPROVED: "bg-amber-100 text-amber-700",
  IMPLEMENTING: "bg-indigo-100 text-indigo-700",
  TESTING: "bg-indigo-100 text-indigo-700",
  PR_CREATED: "bg-emerald-100 text-emerald-700",
  RESOLVED: "bg-green-200 text-green-800",
  FAILED: "bg-red-100 text-red-700",
};

export default function StatusBadge({ status }) {
  return (
    <span className={`px-2 py-0.5 rounded-full text-xs font-medium ${COLORS[status] || "bg-slate-100 text-slate-600"}`}>
      {status}
    </span>
  );
}
