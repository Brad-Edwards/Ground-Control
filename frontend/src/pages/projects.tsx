import {
  FormField,
  inputClass,
  primaryButton,
  secondaryButton,
} from "@/components/ui/form-field";
import { Modal } from "@/components/ui/modal";
import { useToast } from "@/components/ui/toast";
import { useProjects } from "@/hooks/use-projects";
import type { ProjectResponse } from "@/hooks/use-projects";
import { apiFetch } from "@/lib/api-client";
import { queryClient } from "@/lib/query-client";
import type { ProjectRequest, UpdateProjectRequest } from "@/types/api";
import { useMutation } from "@tanstack/react-query";
import { FolderOpen, Plus } from "lucide-react";
import { useState } from "react";
import { useNavigate } from "react-router-dom";

export function Projects() {
  const { data: projects = [], isLoading } = useProjects();
  const navigate = useNavigate();
  const { toast } = useToast();
  const [createOpen, setCreateOpen] = useState(false);
  const [editTarget, setEditTarget] = useState<ProjectResponse | null>(null);

  const createMutation = useMutation({
    mutationFn: (data: ProjectRequest) =>
      apiFetch<ProjectResponse>("/projects", {
        method: "POST",
        body: data,
      }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["projects"] });
      setCreateOpen(false);
      toast({ title: "Project created", variant: "success" });
    },
    onError: (err) =>
      toast({
        title: "Failed to create project",
        description: err.message,
        variant: "error",
      }),
  });

  const updateMutation = useMutation({
    mutationFn: ({
      identifier,
      data,
    }: {
      identifier: string;
      data: UpdateProjectRequest;
    }) =>
      apiFetch<ProjectResponse>(`/projects/${identifier}`, {
        method: "PUT",
        body: data,
      }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["projects"] });
      setEditTarget(null);
      toast({ title: "Project updated", variant: "success" });
    },
    onError: (err) =>
      toast({
        title: "Failed to update project",
        description: err.message,
        variant: "error",
      }),
  });

  if (isLoading) {
    return <div className="h-8 w-48 animate-pulse rounded bg-muted" />;
  }

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-semibold">Projects</h1>
        <button
          type="button"
          className="inline-flex items-center gap-2 rounded-md bg-primary px-3 py-2 text-sm font-medium text-primary-foreground hover:bg-primary/90"
          onClick={() => setCreateOpen(true)}
        >
          <Plus className="h-4 w-4" /> New Project
        </button>
      </div>

      {projects.length === 0 ? (
        <div className="flex flex-col items-center justify-center gap-4 py-20 text-center">
          <FolderOpen className="h-12 w-12 text-muted-foreground" />
          <p className="text-muted-foreground">
            No projects yet. Create one to get started.
          </p>
        </div>
      ) : (
        <div className="space-y-3">
          {projects.map((p) => (
            <div
              key={p.id}
              className="flex items-center gap-4 rounded-lg border border-border bg-card p-4"
            >
              <div className="flex-1">
                <div className="flex items-center gap-2">
                  <span className="font-mono text-sm text-muted-foreground">
                    {p.identifier}
                  </span>
                  <span className="font-medium">{p.name}</span>
                </div>
                {p.description && (
                  <p className="mt-1 text-sm text-muted-foreground">
                    {p.description}
                  </p>
                )}
                <p className="mt-1 text-xs text-muted-foreground">
                  Created {new Date(p.createdAt).toLocaleDateString()}
                </p>
              </div>
              <div className="flex gap-2">
                <button
                  type="button"
                  className={secondaryButton}
                  onClick={() => navigate(`/p/${p.identifier}/`)}
                >
                  Open
                </button>
                <button
                  type="button"
                  className={secondaryButton}
                  onClick={() => setEditTarget(p)}
                >
                  Edit
                </button>
              </div>
            </div>
          ))}
        </div>
      )}

      {/* Create modal */}
      <Modal open={createOpen} onOpenChange={setCreateOpen} title="New Project">
        <CreateProjectForm
          onSubmit={(data) => createMutation.mutate(data)}
          onCancel={() => setCreateOpen(false)}
          loading={createMutation.isPending}
        />
      </Modal>

      {/* Edit modal */}
      <Modal
        open={!!editTarget}
        onOpenChange={(open) => {
          if (!open) setEditTarget(null);
        }}
        title="Edit Project"
      >
        {editTarget && (
          <EditProjectForm
            project={editTarget}
            onSubmit={(data) =>
              updateMutation.mutate({
                identifier: editTarget.identifier,
                data,
              })
            }
            onCancel={() => setEditTarget(null)}
            loading={updateMutation.isPending}
          />
        )}
      </Modal>
    </div>
  );
}

function CreateProjectForm({
  onSubmit,
  onCancel,
  loading,
}: {
  onSubmit: (data: ProjectRequest) => void;
  onCancel: () => void;
  loading?: boolean;
}) {
  const [identifier, setIdentifier] = useState("");
  const [name, setName] = useState("");
  const [description, setDescription] = useState("");

  return (
    <form
      onSubmit={(e) => {
        e.preventDefault();
        onSubmit({
          identifier,
          name,
          description: description || undefined,
        });
      }}
      className="space-y-4"
    >
      <FormField label="Identifier">
        <input
          className={inputClass}
          value={identifier}
          onChange={(e) => setIdentifier(e.target.value)}
          placeholder="my-project (lowercase, hyphens)"
          pattern="[a-z0-9-]+"
          required
        />
      </FormField>
      <FormField label="Name">
        <input
          className={inputClass}
          value={name}
          onChange={(e) => setName(e.target.value)}
          placeholder="My Project"
          required
        />
      </FormField>
      <FormField label="Description">
        <textarea
          className={`${inputClass} min-h-[60px] resize-y`}
          value={description}
          onChange={(e) => setDescription(e.target.value)}
          placeholder="Optional description"
          rows={2}
        />
      </FormField>
      <div className="flex justify-end gap-3 pt-2">
        <button type="button" className={secondaryButton} onClick={onCancel}>
          Cancel
        </button>
        <button type="submit" className={primaryButton} disabled={loading}>
          {loading ? "Creating..." : "Create"}
        </button>
      </div>
    </form>
  );
}

function EditProjectForm({
  project,
  onSubmit,
  onCancel,
  loading,
}: {
  project: ProjectResponse;
  onSubmit: (data: UpdateProjectRequest) => void;
  onCancel: () => void;
  loading?: boolean;
}) {
  const [name, setName] = useState(project.name);
  const [description, setDescription] = useState(project.description ?? "");

  return (
    <form
      onSubmit={(e) => {
        e.preventDefault();
        onSubmit({ name, description: description || undefined });
      }}
      className="space-y-4"
    >
      <FormField label="Name">
        <input
          className={inputClass}
          value={name}
          onChange={(e) => setName(e.target.value)}
          required
        />
      </FormField>
      <FormField label="Description">
        <textarea
          className={`${inputClass} min-h-[60px] resize-y`}
          value={description}
          onChange={(e) => setDescription(e.target.value)}
          rows={2}
        />
      </FormField>
      <div className="flex justify-end gap-3 pt-2">
        <button type="button" className={secondaryButton} onClick={onCancel}>
          Cancel
        </button>
        <button type="submit" className={primaryButton} disabled={loading}>
          {loading ? "Saving..." : "Save"}
        </button>
      </div>
    </form>
  );
}
