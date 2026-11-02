export const ROLE_LABELS = {
  WORKSPACE_ADMIN: "Workspace Admin", PROJECT_MANAGER: "Project Manager", QA: "QA", DEVELOPER: "Developer",
};
export const PROJECT_ROLES = ["PROJECT_MANAGER", "QA", "DEVELOPER"];
export function roleLabel(role) { return ROLE_LABELS[role] || role; }
