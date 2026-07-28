// Split from admin.tsx under issue #1467 for the 500-LOC limit
// (docs/CODING_STANDARDS.md). Declarations are unchanged.

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
  ImportResultResponse,
  PackRegistryEntryResponse,
  PackRegistryImportFormat,
  SyncResultResponse,
} from "@/types/api";
import { CONTROL_FUNCTIONS, PACK_REGISTRY_IMPORT_FORMATS } from "@/types/api";
import { Download, Upload } from "lucide-react";
import { useRef, useState } from "react";

export function PackRegistryImport() {
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

export function StrictDocImport() {
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

export function GitHubSync() {
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
