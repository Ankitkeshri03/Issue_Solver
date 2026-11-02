const API_BASE = import.meta.env.VITE_API_BASE_URL || "http://localhost:8081";

function authHeaders() {
  const token = localStorage.getItem("token");
  return token ? { Authorization: `Bearer ${token}` } : {};
}

async function request(path, options = {}) {
  const res = await fetch(`${API_BASE}${path}`, {
    ...options,
    headers: {
      "Content-Type": "application/json",
      ...authHeaders(),
      ...(options.headers || {}),
    },
  });

  if (!res.ok) {
    let message = `Request failed: ${res.status}`;
    try {
      const body = await res.json();
      message = body.error || body.detail || message;
    } catch {
      // no JSON body
    }
    const error = new Error(message);
    error.status = res.status;
    throw error;
  }

  if (res.status === 204 || res.status === 202) return null;
  const text = await res.text();
  return text ? JSON.parse(text) : null;
}

export const api = {
  previewInvitation: (token) => request("/api/auth/invitations/preview", { method: "POST", body: JSON.stringify({ token }) }),
  activateInvitation: (data) => request("/api/auth/invitations/activate", { method: "POST", body: JSON.stringify(data) }),
  login: (data) => request("/api/auth/login", { method: "POST", body: JSON.stringify(data) }),
  forgotPassword: (email) => request("/api/auth/forgot-password", { method: "POST", body: JSON.stringify({ email }) }),
  resetPassword: (data) => request("/api/auth/reset-password", { method: "POST", body: JSON.stringify(data) }),

  me: () => request("/api/users/me"),
  listRepos: (projectId) => request(`/api/repos${projectId ? `?projectId=${projectId}` : ""}`),
  connectRepo: (owner, repo, projectId) =>
    request("/api/repos/connect", { method: "POST", body: JSON.stringify({ owner, repo, projectId }) }),
  listGithubIssues: (repoId) => request(`/api/repos/${repoId}/issues`),
  listAvailableGithubRepos: () => request("/api/repos/github/available"),

  getGithubStatus: () => request("/api/users/me/github"),
  connectGithubAccount: (token) =>
    request("/api/users/me/github", { method: "POST", body: JSON.stringify({ token }) }),
  disconnectGithubAccount: () => request("/api/users/me/github", { method: "DELETE" }),

  listTickets: (params = {}) => {
    const qs = new URLSearchParams(params).toString();
    return request(`/api/tickets${qs ? `?${qs}` : ""}`);
  },
  getTicket: (id, options = {}) => request(`/api/tickets/${id}`, options),
  createTicket: (data) => request("/api/tickets", { method: "POST", body: JSON.stringify(data) }),
  assignTicket: (id, developerId) =>
    request(`/api/tickets/${id}/assign`, { method: "POST", body: JSON.stringify({ developerId }) }),
  unassignTicket: (id) => request(`/api/tickets/${id}/assignment`, { method: "DELETE" }),

  analyzeTicket: (id) => request(`/api/tickets/${id}/analyze`, { method: "POST" }),
  approveTicket: (id) => request(`/api/tickets/${id}/approve`, { method: "POST" }),
  getSteps: (id, options = {}) => request(`/api/tickets/${id}/steps`, options),

  streamUrl: (id) => `${API_BASE}/api/tickets/${id}/stream?token=${encodeURIComponent(localStorage.getItem("token") || "")}`,
  listDevelopers: (projectId) => request(`/api/users?role=DEVELOPER&projectId=${projectId}`),
  setPriority: (id, priority) => request(`/api/tickets/${id}/priority`, { method: "PUT", body: JSON.stringify({ priority }) }),
  updateReport: (id, report) => request(`/api/tickets/${id}/report`, { method: "PUT", body: JSON.stringify(report) }),
  verifyTicket: (id, notes) => request(`/api/tickets/${id}/verify`, { method: "POST", body: JSON.stringify({ notes }) }),
  reopenTicket: (id, notes) => request(`/api/tickets/${id}/reopen`, { method: "POST", body: JSON.stringify({ notes }) }),
  ticketActivity: (id) => request(`/api/tickets/${id}/activity`),
  listWorkspaces: () => request("/api/workspaces"),
  getWorkspace: (id) => request(`/api/workspaces/${id}`),
  listProjects: (workspaceId) => request(`/api/projects${workspaceId ? `?workspaceId=${workspaceId}` : ""}`),
  getProject: (id) => request(`/api/projects/${id}`),
  createProject: (workspaceId, data) => request(`/api/workspaces/${workspaceId}/projects`, { method: "POST", body: JSON.stringify(data) }),
  workspaceMembers: (id) => request(`/api/workspaces/${id}/members`),
  setWorkspaceAdmin: (id, userId, admin) => request(`/api/workspaces/${id}/members/${userId}`, { method: "PUT", body: JSON.stringify({ admin }) }),
  removeWorkspaceMember: (id, userId) => request(`/api/workspaces/${id}/members/${userId}`, { method: "DELETE" }),
  projectMembers: (id) => request(`/api/projects/${id}/members`),
  availableProjectMembers: (id) => request(`/api/projects/${id}/available-members`),
  projectInvitations: (id) => request(`/api/projects/${id}/invitations`),
  setProjectRole: (id, userId, role) => request(`/api/projects/${id}/members/${userId}`, { method: "PUT", body: JSON.stringify({ role }) }),
  removeProjectMember: (id, userId) => request(`/api/projects/${id}/members/${userId}`, { method: "DELETE" }),
  listInvitations: (id) => request(`/api/workspaces/${id}/invitations`),
  inviteMember: (id, data) => request(`/api/workspaces/${id}/invitations`, { method: "POST", body: JSON.stringify(data) }),
  revokeInvitation: (id, invitationId) => request(`/api/workspaces/${id}/invitations/${invitationId}`, { method: "DELETE" }),
  invitationDetails: (token) => request("/api/invitations/details", { method: "POST", body: JSON.stringify({ token }) }),
  acceptInvitation: (token) => request("/api/invitations/accept", { method: "POST", body: JSON.stringify({ token }) }),
};

export { API_BASE };
