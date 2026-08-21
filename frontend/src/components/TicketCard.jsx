import React from "react";
import { Link } from "react-router-dom";
import StatusBadge from "./StatusBadge.jsx";

export default function TicketCard({ ticket }) {
  return (
    <Link
      to={`/tickets/${ticket.id}`}
      className="block bg-white border rounded-lg p-4 hover:shadow-sm transition-shadow"
    >
      <div className="flex items-start justify-between gap-4">
        <div>
          <p className="text-xs text-slate-400">
            {ticket.repoFullName} #{ticket.githubIssueNumber}
          </p>
          <h3 className="font-medium text-slate-800">{ticket.title}</h3>
          {ticket.assignedToUsername && (
            <p className="text-xs text-slate-500 mt-1">Assigned to {ticket.assignedToUsername}</p>
          )}
        </div>
        <StatusBadge status={ticket.status} />
      </div>
    </Link>
  );
}
