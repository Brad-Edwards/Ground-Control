// @vitest-environment jsdom

import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { renderHook, waitFor } from "@testing-library/react";
import { createElement } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { useWorkflowRunStream } from "../use-workflow-run-stream";
import { workflowRunKeys } from "../workflow-run-keys";

const PROJECT = "ground-control";

/** Minimal EventSource stand-in: jsdom has none, and the hook's contract is what it does with frames. */
class FakeEventSource {
  static instances: FakeEventSource[] = [];

  readonly listeners = new Map<string, Set<EventListener>>();
  onopen: (() => void) | null = null;
  onerror: (() => void) | null = null;
  closed = false;

  constructor(
    readonly url: string,
    readonly init?: { withCredentials?: boolean },
  ) {
    FakeEventSource.instances.push(this);
  }

  addEventListener(type: string, listener: EventListener) {
    const set = this.listeners.get(type) ?? new Set();
    set.add(listener);
    this.listeners.set(type, set);
  }

  removeEventListener(type: string, listener: EventListener) {
    this.listeners.get(type)?.delete(listener);
  }

  close() {
    this.closed = true;
  }

  emit(type: string, data: unknown) {
    const payload = typeof data === "string" ? data : JSON.stringify(data);
    for (const listener of this.listeners.get(type) ?? []) {
      listener(new MessageEvent(type, { data: payload }));
    }
  }
}

function run(overrides: Record<string, unknown> = {}) {
  return {
    id: "run-1",
    project: PROJECT,
    finalState: "RUNNING",
    branch: "1436-live-telemetry-sse-stream",
    ...overrides,
  };
}

function phaseEvent(overrides: Record<string, unknown> = {}) {
  return {
    id: "event-1",
    runId: "run-1",
    project: PROJECT,
    phase: "ci",
    eventType: "COMPLETED",
    ...overrides,
  };
}

let queryClient: QueryClient;

function wrapper({ children }: { children: React.ReactNode }) {
  return createElement(QueryClientProvider, { client: queryClient }, children);
}

function latestSource(): FakeEventSource {
  const source = FakeEventSource.instances.at(-1);
  if (!source) {
    throw new Error("expected the hook to have opened an EventSource");
  }
  return source;
}

beforeEach(() => {
  FakeEventSource.instances = [];
  vi.stubGlobal("EventSource", FakeEventSource);
  queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe("useWorkflowRunStream connection lifecycle", () => {
  it("opens one same-origin credentialed stream for the project", () => {
    renderHook(() => useWorkflowRunStream(PROJECT), { wrapper });

    expect(FakeEventSource.instances).toHaveLength(1);
    expect(latestSource().url).toBe(
      "/api/v1/workflow-runs/stream?project=ground-control",
    );
    expect(latestSource().init?.withCredentials).toBe(true);
  });

  it("closes the stream on unmount", () => {
    const { unmount } = renderHook(() => useWorkflowRunStream(PROJECT), {
      wrapper,
    });
    const source = latestSource();

    unmount();

    expect(source.closed).toBe(true);
  });

  it("replaces the connection when the project changes", () => {
    const { rerender } = renderHook(
      ({ project }) => useWorkflowRunStream(project),
      { wrapper, initialProps: { project: PROJECT } },
    );
    const first = latestSource();

    rerender({ project: "other-project" });

    expect(first.closed).toBe(true);
    expect(FakeEventSource.instances).toHaveLength(2);
    expect(latestSource().url).toContain("project=other-project");
  });

  it("reports degraded without opening a stream when no project is selected", () => {
    const { result } = renderHook(() => useWorkflowRunStream(""), { wrapper });

    expect(FakeEventSource.instances).toHaveLength(0);
    expect(result.current.status).toBe("degraded");
  });
});

describe("useWorkflowRunStream status", () => {
  it("reports live once connected and resynchronizes the snapshots", async () => {
    const invalidate = vi.spyOn(queryClient, "invalidateQueries");
    const { result } = renderHook(() => useWorkflowRunStream(PROJECT), {
      wrapper,
    });

    latestSource().onopen?.();

    await waitFor(() => expect(result.current.status).toBe("live"));
    // Delivery is best-effort, so connecting must refetch rather than trust the pushed feed alone.
    expect(invalidate).toHaveBeenCalledWith({
      queryKey: workflowRunKeys.runs(PROJECT),
    });
    expect(invalidate).toHaveBeenCalledWith({
      queryKey: workflowRunKeys.aggregatePrefix(PROJECT),
    });
  });

  it("reports degraded on transport error so the caller re-arms polling", async () => {
    const { result } = renderHook(() => useWorkflowRunStream(PROJECT), {
      wrapper,
    });
    latestSource().onopen?.();
    await waitFor(() => expect(result.current.status).toBe("live"));

    latestSource().onerror?.();

    await waitFor(() => expect(result.current.status).toBe("degraded"));
  });
});

describe("useWorkflowRunStream cache reconciliation", () => {
  it("inserts a pushed run into the existing run-list cache", async () => {
    queryClient.setQueryData(workflowRunKeys.runs(PROJECT), []);
    renderHook(() => useWorkflowRunStream(PROJECT), { wrapper });

    latestSource().emit("workflow-run", run());

    await waitFor(() =>
      expect(queryClient.getQueryData(workflowRunKeys.runs(PROJECT))).toEqual([
        run(),
      ]),
    );
  });

  it("replaces a run in place by id rather than duplicating it", async () => {
    queryClient.setQueryData(workflowRunKeys.runs(PROJECT), [run()]);
    renderHook(() => useWorkflowRunStream(PROJECT), { wrapper });

    latestSource().emit("workflow-run", run({ finalState: "MERGED" }));

    await waitFor(() => {
      const cached = queryClient.getQueryData<Array<{ finalState: string }>>(
        workflowRunKeys.runs(PROJECT),
      );
      expect(cached).toHaveLength(1);
      expect(cached?.[0]?.finalState).toBe("MERGED");
    });
  });

  it("reconciles a phase event into a mounted per-run event query", async () => {
    const key = workflowRunKeys.runEvents(PROJECT, "run-1");
    queryClient.setQueryData(key, []);
    renderHook(() => useWorkflowRunStream(PROJECT), { wrapper });

    latestSource().emit("phase-event", phaseEvent());

    await waitFor(() =>
      expect(queryClient.getQueryData(key)).toEqual([phaseEvent()]),
    );
  });

  it("appends a new phase event, preserving the REST oldest-first ordering", async () => {
    // GET /workflow-runs/{runId}/events is documented oldest-first. Prepending here would make
    // turning the stream on silently reverse the ordering of the same cache the REST read fills.
    const key = workflowRunKeys.runEvents(PROJECT, "run-1");
    queryClient.setQueryData(key, [
      phaseEvent({ id: "event-1", phase: "ci" }),
      phaseEvent({ id: "event-2", phase: "sonarcloud" }),
    ]);
    renderHook(() => useWorkflowRunStream(PROJECT), { wrapper });

    latestSource().emit(
      "phase-event",
      phaseEvent({ id: "event-3", phase: "ready_for_review" }),
    );

    await waitFor(() => {
      const cached = queryClient.getQueryData<Array<{ id: string }>>(key);
      expect(cached?.map((event) => event.id)).toEqual([
        "event-1",
        "event-2",
        "event-3",
      ]);
    });
  });

  it("replaces a re-delivered phase event in place rather than appending a duplicate", async () => {
    const key = workflowRunKeys.runEvents(PROJECT, "run-1");
    queryClient.setQueryData(key, [
      phaseEvent({ id: "event-1", phase: "ci" }),
      phaseEvent({ id: "event-2", phase: "sonarcloud" }),
    ]);
    renderHook(() => useWorkflowRunStream(PROJECT), { wrapper });

    // Delivery is best-effort and may duplicate; a repeat must not grow the list or reorder it.
    latestSource().emit(
      "phase-event",
      phaseEvent({ id: "event-1", phase: "ci", outcome: "clean" }),
    );

    await waitFor(() => {
      const cached = queryClient.getQueryData<Array<{ id: string }>>(key);
      expect(cached?.map((event) => event.id)).toEqual(["event-1", "event-2"]);
    });
  });

  it("does not fabricate a per-run event list that was never fetched", async () => {
    const key = workflowRunKeys.runEvents(PROJECT, "run-1");
    renderHook(() => useWorkflowRunStream(PROJECT), { wrapper });

    latestSource().emit("phase-event", phaseEvent());

    await waitFor(() => expect(queryClient.getQueryData(key)).toBeUndefined());
  });

  it("invalidates aggregates instead of recomputing them in the browser", async () => {
    const invalidate = vi.spyOn(queryClient, "invalidateQueries");
    renderHook(() => useWorkflowRunStream(PROJECT), { wrapper });

    latestSource().emit("workflow-run", run());

    await waitFor(() =>
      expect(invalidate).toHaveBeenCalledWith({
        queryKey: workflowRunKeys.aggregatePrefix(PROJECT),
      }),
    );
  });
});

describe("useWorkflowRunStream ingress validation", () => {
  it("rejects a cross-project frame, closes the stream, and resynchronizes", async () => {
    queryClient.setQueryData(workflowRunKeys.runs(PROJECT), []);
    const { result } = renderHook(() => useWorkflowRunStream(PROJECT), {
      wrapper,
    });
    const source = latestSource();

    source.emit("workflow-run", run({ project: "someone-else" }));

    await waitFor(() => expect(result.current.status).toBe("degraded"));
    expect(source.closed).toBe(true);
    // The foreign payload never reached the cache.
    expect(queryClient.getQueryData(workflowRunKeys.runs(PROJECT))).toEqual([]);
  });

  it("rejects a malformed frame without poisoning the cache", async () => {
    queryClient.setQueryData(workflowRunKeys.runs(PROJECT), []);
    const { result } = renderHook(() => useWorkflowRunStream(PROJECT), {
      wrapper,
    });

    latestSource().emit("workflow-run", "{not json");

    await waitFor(() => expect(result.current.status).toBe("degraded"));
    expect(queryClient.getQueryData(workflowRunKeys.runs(PROJECT))).toEqual([]);
  });

  it("rejects a frame missing required identity fields", async () => {
    queryClient.setQueryData(workflowRunKeys.runs(PROJECT), []);
    const { result } = renderHook(() => useWorkflowRunStream(PROJECT), {
      wrapper,
    });

    latestSource().emit("workflow-run", { project: PROJECT });

    await waitFor(() => expect(result.current.status).toBe("degraded"));
    expect(queryClient.getQueryData(workflowRunKeys.runs(PROJECT))).toEqual([]);
  });
});
