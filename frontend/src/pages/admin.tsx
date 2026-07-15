import {
  FormField,
  inputClass,
  primaryButton,
} from "@/components/ui/form-field";
import { useToast } from "@/components/ui/toast";
import { useProjectContext } from "@/contexts/project-context";
import { apiFetch, apiUpload } from "@/lib/api-client";
import type {
  ControlFunction,
  GitHubIssueResponse,
  ImportResultResponse,
  PackRegistryEntryResponse,
  PackRegistryImportFormat,
  SyncResultResponse,
} from "@/types/api";
import { CONTROL_FUNCTIONS, PACK_REGISTRY_IMPORT_FORMATS } from "@/types/api";
import { Database, Download, GitBranch, Settings, Upload } from "lucide-react";
import { useRef, useState } from "react";

export function Admin() {
  const { activeProject } = useProjectContext();

  if (!activeProject) {
    return (
      <div className="flex flex-col items-center justify-center gap-4 py-20 text-center">
        <Settings className="h-12 w-12 text-muted-foreground" />
        <h1 className="text-2xl font-semibold">Admin</h1>
        <p className="text-muted-foreground">
          Select a project to access admin tools.
        </p>
      </div>
    );
  }

  return (
    <div className="space-y-6">
      <h1 className="text-2xl font-semibold">Admin</h1>
      <div className="grid gap-6 lg:grid-cols-2">
        <PackRegistryImport />
        <StrictDocImport />
        <GitHubSync />
        <GitHubIssueCreation />
        <GraphMaterialization />
      </div>
    </div>
  );
}

function PackRegistryImport() {
  const { activeProject } = useProjectContext();
  const { toast } = useToast();
  const fileRef = useRef<HTMLInputElement>(null);
  const [loading, setLoading] = useState(false);
  const [result, setResult] = useState<PackRegistryEntryResponse | null>(null);
  const [format, setFormat] = useState<PackRegistryImportFormat>("AUTO");
  const [packId, setPackId] = useState("");
  const [version, setVersion] = useState("");
  const [publisher, setPublisher] = useState("");
  const [description, setDescription] = useState("");
  const [sourceUrl, setSourceUrl] = useState("");
  const [defaultControlFunction, setDefaultControlFunction] =
    useState<ControlFunction>("PREVENTIVE");

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    const file = fileRef.current?.files?.[0];
    if (!file) return;

    setLoading(true);
    setResult(null);
    try {
      const formData = new FormData();
      formData.append("file", file);
      const options = {
        format,
        packId: packId || undefined,
        version: version || undefined,
        publisher: publisher || undefined,
        description: description || undefined,
        sourceUrl: sourceUrl || undefined,
        defaultControlFunction,
      };
      formData.append(
        "options",
        new Blob([JSON.stringify(options)], { type: "application/json" }),
      );

      // ADR-037: authorize via the browser session cookie + CSRF, not a bearer
      // token in sessionStorage. The user must already be signed in as ROLE_ADMIN
      // for /api/v1/pack-registry/** to accept the call; apiUpload echoes the
      // XSRF-TOKEN cookie via X-XSRF-TOKEN automatically.
      const data = await apiUpload<PackRegistryEntryResponse>(
        "/pack-registry/import",
        formData,
        { params: { project: activeProject?.identifier } },
      );
      setResult(data);
      toast({ title: "Pack imported", variant: "success" });
    } catch (err) {
      toast({
        title: "Pack import failed",
        description: err instanceof Error ? err.message : "Unknown error",
        variant: "error",
      });
    } finally {
      setLoading(false);
    }
  }

  return (
    <div className="rounded-lg border border-border bg-card p-5">
      <h3 className="mb-4 flex items-center gap-2 text-base font-medium">
        <Upload className="h-5 w-5 text-primary" /> Pack Registry Import
      </h3>
      <p className="mb-4 text-sm text-muted-foreground">
        Upload OSCAL catalog JSON or a Ground Control pack manifest and register
        it directly in the pack registry for the active project.
      </p>
      <form onSubmit={handleSubmit} className="space-y-3">
        <p className="text-xs text-muted-foreground">
          Authorized via your signed-in session — no bearer token field. You
          must be signed in as an admin for this form to succeed.
        </p>
        <div className="grid gap-3 md:grid-cols-2">
          <FormField label="Source File (.json)">
            <input
              ref={fileRef}
              type="file"
              accept=".json,application/json"
              className={inputClass}
              required
            />
          </FormField>
          <FormField label="Format">
            <select
              className={inputClass}
              value={format}
              onChange={(e) =>
                setFormat(e.target.value as PackRegistryImportFormat)
              }
            >
              {PACK_REGISTRY_IMPORT_FORMATS.map((value) => (
                <option key={value} value={value}>
                  {value}
                </option>
              ))}
            </select>
          </FormField>
        </div>
        <div className="grid gap-3 md:grid-cols-2">
          <FormField label="Pack ID Override">
            <input
              className={inputClass}
              value={packId}
              onChange={(e) => setPackId(e.target.value)}
              placeholder="nist-sp800-53-rev5"
            />
          </FormField>
          <FormField label="Version Override">
            <input
              className={inputClass}
              value={version}
              onChange={(e) => setVersion(e.target.value)}
              placeholder="5.1.0"
            />
          </FormField>
        </div>
        <div className="grid gap-3 md:grid-cols-2">
          <FormField label="Publisher Override">
            <input
              className={inputClass}
              value={publisher}
              onChange={(e) => setPublisher(e.target.value)}
              placeholder="NIST"
            />
          </FormField>
          <FormField label="Default Control Function">
            <select
              className={inputClass}
              value={defaultControlFunction}
              onChange={(e) =>
                setDefaultControlFunction(e.target.value as ControlFunction)
              }
            >
              {CONTROL_FUNCTIONS.map((value) => (
                <option key={value} value={value}>
                  {value}
                </option>
              ))}
            </select>
          </FormField>
        </div>
        <FormField label="Description Override">
          <input
            className={inputClass}
            value={description}
            onChange={(e) => setDescription(e.target.value)}
            placeholder="Optional description override"
          />
        </FormField>
        <FormField label="Source URL Override">
          <input
            className={inputClass}
            value={sourceUrl}
            onChange={(e) => setSourceUrl(e.target.value)}
            placeholder="https://example.com/catalog.json"
          />
        </FormField>
        <button type="submit" className={primaryButton} disabled={loading}>
          {loading ? "Importing..." : "Import Pack"}
        </button>
      </form>
      {result && (
        <div className="mt-4 space-y-1 rounded bg-accent p-3 text-xs">
          <p>
            Registered: {result.packId}@{result.version}
          </p>
          <p>Type: {result.packType}</p>
          <p>Status: {result.catalogStatus}</p>
          <p>Control entries: {result.controlPackEntries?.length ?? 0}</p>
        </div>
      )}
    </div>
  );
}

function StrictDocImport() {
  const { activeProject } = useProjectContext();
  const { toast } = useToast();
  const fileRef = useRef<HTMLInputElement>(null);
  const [loading, setLoading] = useState(false);
  const [result, setResult] = useState<ImportResultResponse | null>(null);

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    const file = fileRef.current?.files?.[0];
    if (!file) return;

    setLoading(true);
    setResult(null);
    try {
      const formData = new FormData();
      formData.append("file", file);
      const data = await apiUpload<ImportResultResponse>(
        "/admin/import/strictdoc",
        formData,
        { params: { project: activeProject?.identifier } },
      );
      setResult(data);
      toast({ title: "Import complete", variant: "success" });
    } catch (err) {
      toast({
        title: "Import failed",
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
        <Upload className="h-5 w-5 text-primary" /> StrictDoc Import
      </h3>
      <form onSubmit={handleSubmit} className="space-y-3">
        <FormField label="StrictDoc File (.sdoc)">
          <input
            ref={fileRef}
            type="file"
            accept=".sdoc,.xml"
            className={inputClass}
          />
        </FormField>
        <button type="submit" className={primaryButton} disabled={loading}>
          {loading ? "Importing..." : "Import"}
        </button>
      </form>
      {result && (
        <div className="mt-4 rounded bg-accent p-3 text-xs space-y-1">
          <p>Parsed: {result.requirementsParsed}</p>
          <p>
            Created: {result.requirementsCreated} | Updated:{" "}
            {result.requirementsUpdated}
          </p>
          <p>
            Relations: {result.relationsCreated} created,{" "}
            {result.relationsSkipped} skipped
          </p>
          <p>
            Links: {result.traceabilityLinksCreated} created,{" "}
            {result.traceabilityLinksSkipped} skipped
          </p>
          {result.errors.length > 0 && (
            <div className="text-destructive mt-2">
              {result.errors.map((e) => (
                <p key={e}>{e}</p>
              ))}
            </div>
          )}
        </div>
      )}
    </div>
  );
}

function GitHubSync() {
  const { toast } = useToast();
  const [owner, setOwner] = useState("");
  const [repo, setRepo] = useState("");
  const [loading, setLoading] = useState(false);
  const [result, setResult] = useState<SyncResultResponse | null>(null);

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    setLoading(true);
    setResult(null);
    try {
      const data = await apiFetch<SyncResultResponse>("/admin/sync/github", {
        method: "POST",
        params: { owner, repo },
      });
      setResult(data);
      toast({ title: "Sync complete", variant: "success" });
    } catch (err) {
      toast({
        title: "Sync failed",
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
        <Download className="h-5 w-5 text-primary" /> GitHub Sync
      </h3>
      <form onSubmit={handleSubmit} className="space-y-3">
        <div className="grid grid-cols-2 gap-3">
          <FormField label="Owner">
            <input
              className={inputClass}
              value={owner}
              onChange={(e) => setOwner(e.target.value)}
              placeholder="autarchy-ai"
              required
            />
          </FormField>
          <FormField label="Repository">
            <input
              className={inputClass}
              value={repo}
              onChange={(e) => setRepo(e.target.value)}
              placeholder="Ground-Control"
              required
            />
          </FormField>
        </div>
        <button type="submit" className={primaryButton} disabled={loading}>
          {loading ? "Syncing..." : "Sync"}
        </button>
      </form>
      {result && (
        <div className="mt-4 rounded bg-accent p-3 text-xs space-y-1">
          <p>Fetched: {result.issuesFetched}</p>
          <p>
            Created: {result.issuesCreated} | Updated: {result.issuesUpdated}
          </p>
          <p>Links updated: {result.linksUpdated}</p>
          {result.errors.length > 0 && (
            <div className="text-destructive mt-2">
              {result.errors.map((e) => (
                <p key={e}>{e}</p>
              ))}
            </div>
          )}
        </div>
      )}
    </div>
  );
}

function GitHubIssueCreation() {
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

function GraphMaterialization() {
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
