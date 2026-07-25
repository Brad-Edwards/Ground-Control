import { CloneModal } from "@/components/requirement-detail/clone-modal";
import { DetailsTab } from "@/components/requirement-detail/details-tab";
import { HistoryTab } from "@/components/requirement-detail/history-tab";
import { ImpactTab } from "@/components/requirement-detail/impact-tab";
import { RelationsTab } from "@/components/requirement-detail/relations-tab";
import { TraceabilityTab } from "@/components/requirement-detail/traceability-tab";
import { RequirementForm } from "@/components/requirement-form";
import { StatusBadgeDropdown } from "@/components/status-badge";
import { PriorityBadge, TypeBadge } from "@/components/ui/badge";
import { secondaryButton } from "@/components/ui/form-field";
import { Modal } from "@/components/ui/modal";
import { useToast } from "@/components/ui/toast";
import {
  useArchiveRequirement,
  useRequirement,
  useTransitionStatus,
  useUpdateRequirement,
} from "@/hooks/use-requirements";
import type { UpdateRequirementRequest } from "@/types/api";
import * as Tabs from "@radix-ui/react-tabs";
import { Archive, ArrowLeft, Copy } from "lucide-react";
import { useState } from "react";
import { Link, useNavigate, useParams } from "react-router";

export function RequirementDetail() {
  const { id, projectId } = useParams<{ id: string; projectId: string }>();
  const navigate = useNavigate();
  const { toast } = useToast();
  const { data: req, isLoading } = useRequirement(id);

  const [editOpen, setEditOpen] = useState(false);
  const [cloneOpen, setCloneOpen] = useState(false);

  const safeId = id ?? "";
  const updateMutation = useUpdateRequirement(safeId);
  const transitionMutation = useTransitionStatus(safeId);
  const archiveMutation = useArchiveRequirement(safeId);

  if (isLoading) {
    return (
      <div className="space-y-4">
        <div className="h-8 w-64 animate-pulse rounded bg-muted" />
        <div className="h-48 animate-pulse rounded-lg bg-muted" />
      </div>
    );
  }

  if (!req) {
    return (
      <div className="py-20 text-center text-muted-foreground">
        Requirement not found.
      </div>
    );
  }

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex items-start justify-between">
        <div>
          <Link
            to={`/p/${projectId}/requirements`}
            className="mb-2 inline-flex items-center gap-1 text-sm text-muted-foreground hover:text-foreground"
          >
            <ArrowLeft className="h-4 w-4" /> Back to Requirements
          </Link>
          <div className="flex items-center gap-3">
            <h1 className="text-2xl font-semibold">{req.title}</h1>
            <span className="font-mono text-sm text-muted-foreground">
              {req.uid}
            </span>
          </div>
        </div>
        <div className="flex items-center gap-2">
          <button
            type="button"
            className={secondaryButton}
            onClick={() => setEditOpen(true)}
          >
            Edit
          </button>
          <button
            type="button"
            className={secondaryButton}
            onClick={() => setCloneOpen(true)}
          >
            <Copy className="mr-1 h-4 w-4" /> Clone
          </button>
          {req.status !== "ARCHIVED" && (
            <button
              type="button"
              className={secondaryButton}
              onClick={() =>
                archiveMutation.mutate(undefined, {
                  onSuccess: () =>
                    toast({
                      title: "Requirement archived",
                      variant: "success",
                    }),
                  onError: (err) =>
                    toast({
                      title: "Archive failed",
                      description: err.message,
                      variant: "error",
                    }),
                })
              }
            >
              <Archive className="mr-1 h-4 w-4" /> Archive
            </button>
          )}
        </div>
      </div>

      {/* Metadata cards */}
      <div className="flex flex-wrap items-center gap-3">
        <StatusBadgeDropdown
          status={req.status}
          onTransition={(s) =>
            transitionMutation.mutate(s, {
              onSuccess: () =>
                toast({ title: `Status changed to ${s}`, variant: "success" }),
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
        <span className="text-sm text-muted-foreground">Wave {req.wave}</span>
      </div>

      {/* Tabs */}
      <Tabs.Root defaultValue="details">
        <Tabs.List className="flex gap-1 border-b border-border">
          {["details", "relations", "traceability", "history", "impact"].map(
            (tab) => (
              <Tabs.Trigger
                key={tab}
                value={tab}
                className="border-b-2 border-transparent px-4 py-2 text-sm font-medium text-muted-foreground hover:text-foreground data-[state=active]:border-primary data-[state=active]:text-foreground"
              >
                {tab.charAt(0).toUpperCase() + tab.slice(1)}
              </Tabs.Trigger>
            ),
          )}
        </Tabs.List>

        <Tabs.Content value="details" className="pt-4">
          <DetailsTab req={req} />
        </Tabs.Content>

        <Tabs.Content value="relations" className="pt-4">
          <RelationsTab requirementId={req.id} />
        </Tabs.Content>

        <Tabs.Content value="traceability" className="pt-4">
          <TraceabilityTab requirementId={req.id} />
        </Tabs.Content>

        <Tabs.Content value="history" className="pt-4">
          <HistoryTab requirementId={req.id} />
        </Tabs.Content>

        <Tabs.Content value="impact" className="pt-4">
          <ImpactTab requirementId={req.id} />
        </Tabs.Content>
      </Tabs.Root>

      {/* Edit modal */}
      <Modal
        open={editOpen}
        onOpenChange={setEditOpen}
        title="Edit Requirement"
        className="max-w-2xl"
      >
        <RequirementForm
          mode="edit"
          initial={req}
          onSubmit={(data) =>
            updateMutation.mutate(data as UpdateRequirementRequest, {
              onSuccess: () => {
                setEditOpen(false);
                toast({ title: "Requirement updated", variant: "success" });
              },
              onError: (err) =>
                toast({
                  title: "Update failed",
                  description: err.message,
                  variant: "error",
                }),
            })
          }
          onCancel={() => setEditOpen(false)}
          loading={updateMutation.isPending}
        />
      </Modal>

      {/* Clone modal */}
      <CloneModal
        open={cloneOpen}
        onOpenChange={setCloneOpen}
        requirementId={req.id}
        onCloned={(newReq) =>
          navigate(`/p/${projectId}/requirements/${newReq.id}`)
        }
      />
    </div>
  );
}
