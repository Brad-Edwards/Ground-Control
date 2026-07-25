import { RelationForm } from "@/components/relation-form";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { secondaryButton } from "@/components/ui/form-field";
import { Modal } from "@/components/ui/modal";
import { useToast } from "@/components/ui/toast";
import {
  useCreateRelation,
  useDeleteRelation,
  useRelations,
} from "@/hooks/use-relations";
import type { RelationResponse } from "@/types/api";
import { Plus, Trash2 } from "lucide-react";
import { useState } from "react";
import { Link, useParams } from "react-router";

function RelationRow({
  rel,
  currentId,
  onDelete,
}: {
  rel: RelationResponse;
  currentId: string;
  onDelete: () => void;
}) {
  const { projectId } = useParams<{ projectId: string }>();
  const isSource = rel.sourceId === currentId;
  const otherUid = isSource ? rel.targetUid : rel.sourceUid;
  const otherId = isSource ? rel.targetId : rel.sourceId;

  return (
    <tr className="hover:bg-accent/30">
      <td className="px-3 py-2 text-xs">
        {isSource ? "Outgoing" : "Incoming"}
      </td>
      <td className="px-3 py-2">
        <Link
          to={`/p/${projectId}/requirements/${otherId}`}
          className="font-mono text-xs text-primary hover:underline"
        >
          {otherUid}
        </Link>
      </td>
      <td className="px-3 py-2 text-xs">
        {rel.relationType.replace(/_/g, " ")}
      </td>
      <td className="px-3 py-2 text-xs text-muted-foreground">
        {new Date(rel.createdAt).toLocaleDateString()}
      </td>
      <td className="px-3 py-2">
        <button
          type="button"
          className="rounded p-1 text-muted-foreground hover:bg-destructive/10 hover:text-destructive"
          onClick={onDelete}
        >
          <Trash2 className="h-3.5 w-3.5" />
        </button>
      </td>
    </tr>
  );
}

export function RelationsTab({ requirementId }: { requirementId: string }) {
  const { toast } = useToast();
  const { data: relations = [], isLoading } = useRelations(requirementId);
  const createMutation = useCreateRelation(requirementId);
  const deleteMutation = useDeleteRelation(requirementId);
  const [addOpen, setAddOpen] = useState(false);
  const [deleteTarget, setDeleteTarget] = useState<string | null>(null);

  if (isLoading) return <div className="animate-pulse h-20 bg-muted rounded" />;

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <h3 className="text-sm font-medium">Relations ({relations.length})</h3>
        <button
          type="button"
          className={secondaryButton}
          onClick={() => setAddOpen(true)}
        >
          <Plus className="mr-1 h-4 w-4" /> Add Relation
        </button>
      </div>

      {relations.length > 0 ? (
        <div className="rounded-lg border border-border overflow-hidden">
          <table className="w-full text-sm">
            <thead className="bg-card border-b border-border">
              <tr>
                <th className="px-3 py-2 text-left text-xs font-medium text-muted-foreground">
                  Direction
                </th>
                <th className="px-3 py-2 text-left text-xs font-medium text-muted-foreground">
                  Requirement
                </th>
                <th className="px-3 py-2 text-left text-xs font-medium text-muted-foreground">
                  Type
                </th>
                <th className="px-3 py-2 text-left text-xs font-medium text-muted-foreground">
                  Created
                </th>
                <th className="px-3 py-2 w-10" />
              </tr>
            </thead>
            <tbody className="divide-y divide-border">
              {relations.map((rel) => (
                <RelationRow
                  key={rel.id}
                  rel={rel}
                  currentId={requirementId}
                  onDelete={() => setDeleteTarget(rel.id)}
                />
              ))}
            </tbody>
          </table>
        </div>
      ) : (
        <p className="text-sm text-muted-foreground">No relations.</p>
      )}

      <Modal open={addOpen} onOpenChange={setAddOpen} title="Add Relation">
        <RelationForm
          sourceId={requirementId}
          onSubmit={(data) =>
            createMutation.mutate(data, {
              onSuccess: () => {
                setAddOpen(false);
                toast({ title: "Relation added", variant: "success" });
              },
              onError: (err) =>
                toast({
                  title: "Failed to add relation",
                  description: err.message,
                  variant: "error",
                }),
            })
          }
          onCancel={() => setAddOpen(false)}
          loading={createMutation.isPending}
        />
      </Modal>

      <ConfirmDialog
        open={!!deleteTarget}
        onOpenChange={(open) => {
          if (!open) setDeleteTarget(null);
        }}
        title="Delete Relation"
        description="Are you sure you want to delete this relation?"
        onConfirm={() => {
          if (!deleteTarget) return;
          deleteMutation.mutate(deleteTarget, {
            onSuccess: () => {
              setDeleteTarget(null);
              toast({ title: "Relation deleted", variant: "success" });
            },
            onError: (err) =>
              toast({
                title: "Delete failed",
                description: err.message,
                variant: "error",
              }),
          });
        }}
        loading={deleteMutation.isPending}
      />
    </div>
  );
}
