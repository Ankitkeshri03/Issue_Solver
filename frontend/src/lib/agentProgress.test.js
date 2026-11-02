import test from "node:test";
import assert from "node:assert/strict";
import { mergeSteps, progressStatus, watchAgentProgress } from "./agentProgress.js";

const tick = () => new Promise((resolve) => setImmediate(resolve));

test("unfinished phases stop spinning once the ticket has failed", () => {
  assert.equal(progressStatus({ status: "RUNNING" }, "FAILED"), "STOPPED");
  assert.equal(progressStatus({ status: "RUNNING" }, "ANALYZING"), "RUNNING");
  assert.equal(progressStatus({ status: "DONE" }, "PLAN_READY"), "DONE");
});

function harness(overrides = {}) {
  const state = { status: "ANALYZING", steps: [], snapshots: [], states: [], errors: [], received: [] };
  class Source {
    constructor() { state.source = this; this.handlers = {}; this.closed = false; }
    addEventListener(name, fn) { this.handlers[name] = fn; }
    emit(name, data) { this.handlers[name]?.({ data: JSON.stringify(data) }); }
    close() { this.closed = true; }
  }
  const api = {
    getSteps: async () => state.steps,
    getTicket: async () => ({ status: state.status }),
    streamUrl: () => "/test-stream",
    ...overrides,
  };
  state.stop = watchAgentProgress(1, {
    api, EventSourceClass: Source,
    onSnapshot: (steps, ticket) => state.snapshots.push({ steps, ticket }),
    onStep: (step) => state.received.push(step),
    onState: (value) => state.states.push(value),
    onError: (value) => state.errors.push(value),
    schedule: (fn) => { state.poll = fn; return 1; },
    unschedule: () => { state.timerStopped = true; },
  });
  return state;
}

test("a broken stream reconnects and polling recovers missed steps", async () => {
  const h = harness();
  try {
    await tick();
    h.source.emit("open");
    h.source.emit("error");
    assert.equal(h.source.closed, false);
    assert.ok(h.states.includes("reconnecting"));
    await tick();
    h.steps = [{ id: 2, status: "DONE" }];
    h.poll();
    await tick();
    assert.deepEqual(h.snapshots.at(-1).steps, h.steps);
    h.source.emit("open");
    assert.equal(h.states.at(-1), "live");
  } finally { h.stop(); }
});

test("phase completion refreshes persisted status then closes cleanly", async () => {
  const h = harness();
  try {
    await tick();
    h.status = "PLAN_READY";
    h.source.emit("phase-complete");
    await tick();
    assert.equal(h.states.at(-1), "complete");
    assert.equal(h.source.closed, true);
    assert.equal(h.timerStopped, true);
    h.source.emit("error");
    assert.equal(h.states.at(-1), "complete");
  } finally { h.stop(); }
});

test("loading an already completed analysis shows completion without waiting for a new event", async () => {
  const h = harness({ getTicket: async () => ({ status: "PLAN_READY" }) });
  try {
    await tick();
    assert.equal(h.states.at(-1), "complete");
    assert.equal(h.source.closed, true);
  } finally { h.stop(); }
});

test("interrupted jobs show failed and remain available for retry from the ticket", async () => {
  const h = harness({ getTicket: async () => ({ status: "FAILED" }) });
  try {
    await tick();
    assert.equal(h.states.at(-1), "failed");
    assert.equal(h.source.closed, true);
  } finally { h.stop(); }
});

test("access failures stop retries and display a useful message", async () => {
  const h = harness({ getTicket: async () => { throw Object.assign(new Error("Denied"), { status: 403 }); } });
  try {
    await tick();
    assert.equal(h.states.at(-1), "unauthorized");
    assert.match(h.errors.at(-1), /Sign in again/);
    assert.equal(h.source.closed, true);
  } finally { h.stop(); }
});

test("unmount discards a late snapshot", async () => {
  let resolve;
  const h = harness({ getTicket: () => new Promise((done) => { resolve = done; }) });
  h.stop();
  resolve({ status: "PLAN_READY" });
  await tick();
  assert.equal(h.snapshots.length, 0);
  assert.equal(h.source.closed, true);
});

test("completion during an older snapshot triggers a fresh read", async () => {
  let resolve;
  let reads = 0;
  const h = harness({ getTicket: () => ++reads === 1
    ? new Promise((done) => { resolve = done; })
    : Promise.resolve({ status: "PLAN_READY" }) });
  try {
    h.source.emit("phase-complete");
    resolve({ status: "ANALYZING" });
    await tick();
    assert.equal(reads, 2);
    assert.equal(h.states.at(-1), "complete");
  } finally { h.stop(); }
});

test("snapshot merging preserves newer events and removes duplicate IDs", () => {
  assert.deepEqual(mergeSteps([{ id: 3 }, { id: 2, status: "RUNNING" }],
    [{ id: 1 }, { id: 2, status: "DONE" }]), [{ id: 1 }, { id: 2, status: "DONE" }, { id: 3 }]);
});
