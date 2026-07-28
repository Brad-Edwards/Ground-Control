// Split from admin.tsx under issue #1467 for the 500-LOC limit
// (docs/CODING_STANDARDS.md). Declarations are unchanged.

import {
  FormField,
  inputClass,
  primaryButton,
} from "@/components/ui/form-field";
import { useToast } from "@/components/ui/toast";
import { useProjectContext } from "@/contexts/project-context";
import { apiFetch } from "@/lib/api-client";
import type { GitHubIssueResponse } from "@/types/api";
import { Database, GitBranch } from "lucide-react";
import { useState } from "react";

export function GitHubIssueCreation() {
  const { activeProject } = useProjectContext();
  const { toast } = useToast();
  const [uid, setUid] = useState("");
  const [repo, setRepo] = useState("");
  const [labels, setLabels] = useState("");
  const [loading, setLoading] = useState(false);
  const [result, setResult] = useState<GitHubIssueResponse | null>(null);

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    setLoading(true);
    setResult(null);
    try {
      const data = await apiFetch<GitHubIssueResponse>("/admin/github/issues", {
        method: "POST",
        params: { project: activeProject?.identifier },
        body: {
          requirementUid: uid,
          repo: repo || undefined,
          labels: labels ? labels.split(",").map((l) => l.trim()) : undefined,
        },
      });
      setResult(data);
      toast({ title: "GitHub issue created", variant: "success" });
    } catch (err) {
      toast({
        title: "Failed to create issue",
        description: err instanceof Error ? err.message : "Unknown error",
        variant: "error",
      });
    } finally {
      setLoading(false);
    }
  }

  return (
    <div className="rounded-lg border border-border bg-card p-5">
      <h3 className="flex items-center gap-2 text-base font-medium mb-4">
        <GitBranch className="h-5 w-5 text-primary" /> Create GitHub Issue
      </h3>
      <form onSubmit={handleSubmit} className="space-y-3">
        <FormField label="Requirement UID">
          <input
            className={inputClass}
            value={uid}
            onChange={(e) => setUid(e.target.value)}
            placeholder="GC-A001"
            required
          />
        </FormField>
        <div className="grid grid-cols-2 gap-3">
          <FormField label="Repository (optional)">
            <input
              className={inputClass}
              value={repo}
              onChange={(e) => setRepo(e.target.value)}
              placeholder="owner/repo"
            />
          </FormField>
          <FormField label="Labels (comma-separated)">
            <input
              className={inputClass}
              value={labels}
              onChange={(e) => setLabels(e.target.value)}
              placeholder="requirement, wave-1"
            />
          </FormField>
        </div>
        <button type="submit" className={primaryButton} disabled={loading}>
          {loading ? "Creating..." : "Create Issue"}
        </button>
      </form>
      {result && (
        <div className="mt-4 rounded bg-accent p-3 text-xs space-y-1">
          <p>
            Issue #{result.issueNumber}:{" "}
            <a
              href={result.issueUrl}
              target="_blank"
              rel="noopener noreferrer"
              className="text-primary hover:underline"
            >
              {result.issueUrl}
            </a>
          </p>
          {result.warning && (
            <p className="text-yellow-400">{result.warning}</p>
          )}
        </div>
      )}
    </div>
  );
}

export function GraphMaterialization() {
  const { toast } = useToast();
  const [loading, setLoading] = useState(false);
  const [done, setDone] = useState(false);

  async function handleMaterialize() {
    setLoading(true);
    setDone(false);
    try {
      await apiFetch<void>("/admin/graph/materialize", { method: "POST" });
      setDone(true);
      toast({ title: "Graph materialized", variant: "success" });
    } catch (err) {
      toast({
        title: "Materialization failed",
        description: err instanceof Error ? err.message : "Unknown error",
        variant: "error",
      });
    } finally {
      setLoading(false);
    }
  }

  return (
    <div className="rounded-lg border border-border bg-card p-5">
      <h3 className="flex items-center gap-2 text-base font-medium mb-4">
        <Database className="h-5 w-5 text-primary" /> Graph Materialization
      </h3>
      <p className="text-sm text-muted-foreground mb-4">
        Rebuild the materialized graph for ancestor/descendant queries.
      </p>
      <button
        type="button"
        className={primaryButton}
        onClick={handleMaterialize}
        disabled={loading}
      >
        {loading ? "Materializing..." : "Materialize Graph"}
      </button>
      {done && (
        <p className="mt-3 text-xs text-green-400">
          Graph materialized successfully.
        </p>
      )}
    </div>
  );
}
