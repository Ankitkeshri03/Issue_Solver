import React from "react";
import { Link } from "react-router-dom";
import { useAuth } from "../context/AuthContext.jsx";

export default function Navbar() {
  const { user, logout } = useAuth();
  return (
    <nav className="border-b bg-white px-6 py-3 flex items-center justify-between">
      <Link to="/" className="font-semibold text-slate-800">
        AI Software Engineering Agent
      </Link>
      {user && (
        <div className="flex items-center gap-4 text-sm text-slate-600">
          <span>
            {user.username} <span className="text-slate-400">({user.role})</span>
          </span>
          <button onClick={logout} className="text-red-600 hover:underline">
            Logout
          </button>
        </div>
      )}
    </nav>
  );
}
