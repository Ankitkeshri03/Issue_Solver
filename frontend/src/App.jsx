import React from "react";
import { Route, Routes } from "react-router-dom";
import Navbar from "./components/Navbar.jsx";
import ProtectedRoute from "./components/ProtectedRoute.jsx";
import Login from "./pages/Login.jsx";
import Dashboard from "./pages/Dashboard.jsx";
import TicketDetail from "./pages/TicketDetail.jsx";
import AgentLiveView from "./pages/AgentLiveView.jsx";
import DiffAndPr from "./pages/DiffAndPr.jsx";

export default function App() {
  return (
    <div className="min-h-screen">
      <Navbar />
      <main className="max-w-5xl mx-auto px-6 py-8">
        <Routes>
          <Route path="/login" element={<Login />} />
          <Route
            path="/"
            element={
              <ProtectedRoute>
                <Dashboard />
              </ProtectedRoute>
            }
          />
          <Route
            path="/tickets/:id"
            element={
              <ProtectedRoute>
                <TicketDetail />
              </ProtectedRoute>
            }
          />
          <Route
            path="/tickets/:id/live"
            element={
              <ProtectedRoute>
                <AgentLiveView />
              </ProtectedRoute>
            }
          />
          <Route
            path="/tickets/:id/diff"
            element={
              <ProtectedRoute>
                <DiffAndPr />
              </ProtectedRoute>
            }
          />
        </Routes>
      </main>
    </div>
  );
}
