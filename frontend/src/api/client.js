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
      message = body.error || message;
    } catch {
      // no JSON body
    }
    throw new Error(message);
  }

  if (res.status === 204 || res.status === 202) return null;
  const text = await res.text();
  return text ? JSON.parse(text) : null;
}

export const api = {
  register: (data) => request("/api/auth/register", { method: "POST", body: JSON.stringify(data) }),
  login: (data) => request("/api/auth/login", { method: "POST", body: JSON.stringify(data) }),

  listRepos: () => request("/api/repos"),
  connectRepo: (owner, repo) =>
    request("/api/repos/connect", { method: "POST", body: JSON.stringify({ owner, repo }) }),
  listGithubIssues: (owner, repo) => request(`/api/repos/${owner}/${repo}/issues`),
  listAvailableGithubRepos: () => request("/api/repos/github/available"),

  getGithubStatus: () => request("/api/users/me/github"),
  connectGithubAccount: (token) =>
    request("/api/users/me/github", { method: "POST", body: JSON.stringify({ token }) }),
  disconnectGithubAccount: () => request("/api/users/me/github", { method: "DELETE" }),

  listTickets: (params = {}) => {
    const qs = new URLSearchParams(params).toString();
    return request(`/api/tickets${qs ? `?${qs}` : ""}`);
  },
  getTicket: (id) => request(`/api/tickets/${id}`),
  createTicket: (data) => request("/api/tickets", { method: "POST", body: JSON.stringify(data) }),
  assignTicket: (id, developerId) =>
    request(`/api/tickets/${id}/assign`, { method: "POST", body: JSON.stringify({ developerId }) }),

  analyzeTicket: (id) => request(`/api/tickets/${id}/analyze`, { method: "POST" }),
  approveTicket: (id) => request(`/api/tickets/${id}/approve`, { method: "POST" }),
  getSteps: (id) => request(`/api/tickets/${id}/steps`),

  streamUrl: (id) => `${API_BASE}/api/tickets/${id}/stream?token=${encodeURIComponent(localStorage.getItem("token") || "")}`,
  listDevelopers: () => request("/api/users?role=DEVELOPER"),
};

export { API_BASE };
