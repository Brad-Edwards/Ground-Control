import { RequirementForm } from "@/components/requirement-form";
import { StatusBadgeDropdown } from "@/components/status-badge";
import { PriorityBadge, TypeBadge } from "@/components/ui/badge";
import { useToast } from "@/components/ui/toast";
import {
  useArchiveRequirement,
  useTransitionStatus,
  useUpdateRequirement,
} from "@/hooks/use-requirements";
import type {
  RequirementRequest,
  RequirementResponse,
  UpdateRequirementRequest,
} from "@/types/api";
import { ExternalLink, Pencil } from "lucide-react";
import { useCallback, useState } from "react";
import { Link, useParams } from "react-router";

interface RequirementDetailPanelProps {
  requirement: RequirementResponse;
  onClose: () => void;
}

export function RequirementDetailPanel({
  requirement: req,
  onClose,
}: RequirementDetailPanelProps) {
  const { projectId } = useParams<{ projectId: string }>();
  const { toast } = useToast();
  const [editing, setEditing] = useState(false);

  const transition = useTransitionStatus(req.id);
  const updateMutation = useUpdateRequirement(req.id);
  const archiveMutation = useArchiveRequirement(req.id);

  const handleUpdate = useCallback(
    (data: RequirementRequest | UpdateRequirementRequest) => {
      updateMutation.mutate(data as UpdateRequirementRequest, {
        onSuccess: () => {
          setEditing(false);
          toast({ title: "Requirement updated", variant: "success" });
        },
        onError: (err) => {
          toast({
            title: "Failed to update",
            description: err.message,
            variant: "error",
          });
        },
      });
    },
    [updateMutation, toast],
  );

  function handleArchive() {
    archiveMutation.mutate(undefined, {
      onSuccess: () => {
        toast({ title: "Requirement archived", variant: "success" });
        onClose();
      },
      onError: (err) => {
        toast({
          title: "Failed to archive",
          description: err.message,
          variant: "error",
        });
      },
    });
  }

  if (editing) {
    return (
      <RequirementForm
        mode="edit"
        initial={req}
        onSubmit={handleUpdate}
        onCancel={() => setEditing(false)}
        loading={updateMutation.isPending}
      />
    );
  }

  return (
    <div className="space-y-6">
      {/* Actions */}
      <div className="flex items-center gap-2">
        <button
          type="button"
          className="inline-flex items-center gap-1.5 rounded-md border border-input bg-background px-3 py-1.5 text-sm hover:bg-accent"
          onClick={() => setEditing(true)}
        >
          <Pencil className="h-3.5 w-3.5" />
          Edit
        </button>
        <Link
          to={`/p/${projectId}/requirements/${req.id}`}
          className="inline-flex items-center gap-1.5 rounded-md border border-input bg-background px-3 py-1.5 text-sm hover:bg-accent"
        >
          <ExternalLink className="h-3.5 w-3.5" />
          Full Detail
        </Link>
        {(req.status === "ACTIVE" || req.status === "DEPRECATED") && (
          <button
            type="button"
            className="ml-auto rounded-md border border-destructive/30 px-3 py-1.5 text-sm text-destructive hover:bg-destructive/10"
            onClick={handleArchive}
            disabled={archiveMutation.isPending}
          >
            {archiveMutation.isPending ? "..." : "Archive"}
          </button>
        )}
      </div>

      {/* Metadata */}
      <div className="flex flex-wrap items-center gap-2">
        <StatusBadgeDropdown
          status={req.status}
          onTransition={(s) =>
            transition.mutate(s, {
              onSuccess: () =>
                toast({
                  title: `Status changed to ${s}`,
                  variant: "success",
                }),
              onError: (err) =>
                toast({
                  title: "Transition failed",
                  description: err.message,
                  variant: "error",
                }),
            })
          }
        />
        <PriorityBadge priority={req.priority} />
        <TypeBadge type={req.requirementType} />
        {req.wave != null && (
          <span className="inline-flex items-center rounded-full bg-accent px-2 py-0.5 text-xs font-medium text-accent-foreground">
            Wave {req.wave}
          </span>
        )}
      </div>

      {/* Statement */}
      {req.statement && (
        <section>
          <h3 className="mb-1 text-xs font-medium uppercase tracking-wide text-muted-foreground">
            Statement
          </h3>
          <p className="whitespace-pre-wrap text-sm leading-relaxed">
            {req.statement}
          </p>
        </section>
      )}

      {/* Rationale */}
      {req.rationale && (
        <section>
          <h3 className="mb-1 text-xs font-medium uppercase tracking-wide text-muted-foreground">
            Rationale
          </h3>
          <p className="whitespace-pre-wrap text-sm leading-relaxed text-muted-foreground">
            {req.rationale}
          </p>
        </section>
      )}

      {/* Timestamps */}
      <div className="border-t border-border pt-4 text-xs text-muted-foreground">
        <div className="flex justify-between">
          <span>Created {new Date(req.createdAt).toLocaleString()}</span>
          <span>Updated {new Date(req.updatedAt).toLocaleString()}</span>
        </div>
        {req.archivedAt && (
          <div className="mt-1">
            Archived {new Date(req.archivedAt).toLocaleString()}
          </div>
        )}
      </div>
    </div>
  );
}
