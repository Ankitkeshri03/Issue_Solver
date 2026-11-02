const ACTIVE = new Set(["ANALYZING", "PLAN_APPROVED", "IMPLEMENTING", "TESTING"]);

export function progressStatus(step, ticketStatus) {
  return step.status === "RUNNING" && ticketStatus && !ACTIVE.has(ticketStatus) ? "STOPPED" : step.status;
}

export function mergeSteps(previous, incoming) {
  const byId = new Map(previous.map((step) => [step.id, step]));
  incoming.forEach((step) => byId.set(step.id, step));
  return [...byId.values()].sort((a, b) => a.id - b.id);
}

// Persisted snapshots recover missed events; EventSource reconnects its live stream.
export function watchAgentProgress(id, {
  api, onSnapshot, onStep, onState, onError,
  EventSourceClass = globalThis.EventSource,
  schedule = globalThis.setInterval, unschedule = globalThis.clearInterval,
  pollMs = 5000,
}) {
  let stopped = false;
  let finished = false;
  let stream;
  let timer;
  let pending;
  let controller;

  function finish(state) {
    finished = true;
    stream?.close();
    if (timer !== undefined) unschedule(timer);
    onState(state);
  }

  function refresh() {
    if (stopped || finished) return Promise.resolve();
    if (pending) return pending;
    controller = new AbortController();
    const timeout = setTimeout(() => controller?.abort(), 10000);
    const options = { signal: controller.signal };
    pending = Promise.all([api.getSteps(id, options), api.getTicket(id, options)])
      .then(([steps, ticket]) => {
        if (stopped) return;
        onSnapshot(steps, ticket);
        onError("");
        if (!ACTIVE.has(ticket.status)) {
          finish(ticket.status === "FAILED" ? "failed" : ticket.status === "OPEN" ? "idle" : "complete");
        }
      })
      .catch((error) => {
        if (stopped) return;
        if ([401, 403, 404].includes(error.status)) {
          onError("Your session expired or this ticket is no longer accessible. Sign in again and check your assignment.");
          finish("unauthorized");
        } else {
          onError("Unable to refresh progress. Retrying automatically…");
        }
      })
      .finally(() => { clearTimeout(timeout); pending = null; controller = null; });
    return pending;
  }

  onState("connecting");
  try {
    stream = new EventSourceClass(api.streamUrl(id));
    stream.addEventListener("open", () => {
      if (stopped || finished) return;
      onState("live");
      refresh();
    });
    stream.addEventListener("agent-step", (event) => {
      if (stopped || finished) return;
      try { onStep(JSON.parse(event.data)); }
      catch { refresh(); }
    });
    stream.addEventListener("error", () => {
      if (stopped || finished) return;
      onState("reconnecting");
      refresh();
      // Keep it open: closing here disables the browser's automatic reconnection.
    });
    stream.addEventListener("phase-complete", () => {
      if (stopped || finished) return;
      // If an older snapshot is in flight, fetch again after it settles.
      if (pending) pending.then(() => refresh());
      else refresh();
    });
  } catch {
    onState("reconnecting");
  }
  timer = schedule(refresh, pollMs);
  refresh();

  return () => {
    stopped = true;
    stream?.close();
    unschedule(timer);
    controller?.abort();
  };
}
