import { RequirementDetailPanel } from "@/components/requirement-detail-panel";
import { RequirementForm } from "@/components/requirement-form";
import { FilterSelect } from "@/components/requirements/filter-select";
import { RequirementRow } from "@/components/requirements/requirement-row";
import { RequirementsSkeleton } from "@/components/requirements/requirements-skeleton";
import {
  type SortField,
  SortableHeader,
} from "@/components/requirements/sortable-header";
import { Modal } from "@/components/ui/modal";
import { SlidePanel } from "@/components/ui/slide-panel";
import { useToast } from "@/components/ui/toast";
import { useProjectContext } from "@/contexts/project-context";
import {
  useBulkTransition,
  useCreateRequirement,
  useRequirements,
} from "@/hooks/use-requirements";
import type {
  BulkStatusTransitionResponse,
  RequirementRequest,
  RequirementResponse,
  Status,
  UpdateRequirementRequest,
} from "@/types/api";
import { PRIORITIES, REQUIREMENT_TYPES, STATUSES } from "@/types/api";
import * as Checkbox from "@radix-ui/react-checkbox";
import { Check, FileText, Plus } from "lucide-react";
import { useCallback, useState } from "react";

type SortDir = "asc" | "desc";

export function Requirements() {
  const { activeProject, isLoading: projectLoading } = useProjectContext();
  const { toast } = useToast();

  const [page, setPage] = useState(0);
  const [size, setSize] = useState(25);
  const [sortField, setSortField] = useState<SortField>("uid");
  const [sortDir, setSortDir] = useState<SortDir>("asc");
  const [statusFilter, setStatusFilter] = useState("");
  const [typeFilter, setTypeFilter] = useState("");
  const [priorityFilter, setPriorityFilter] = useState("");
  const [waveFilter, setWaveFilter] = useState("");
  const [searchFilter, setSearchFilter] = useState("");
  const [createOpen, setCreateOpen] = useState(false);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [bulkStatus, setBulkStatus] = useState<Status>("ACTIVE");
  const [detailReq, setDetailReq] = useState<RequirementResponse | null>(null);

  const sort = `${sortField},${sortDir}`;

  const { data, isLoading } = useRequirements({
    status: statusFilter || undefined,
    type: typeFilter || undefined,
    priority: priorityFilter || undefined,
    wave: waveFilter || undefined,
    search: searchFilter || undefined,
    page,
    size,
    sort,
  });

  const createMutation = useCreateRequirement();
  const bulkMutation = useBulkTransition();

  function handleSort(field: SortField) {
    if (sortField === field) {
      setSortDir(sortDir === "asc" ? "desc" : "asc");
    } else {
      setSortField(field);
      setSortDir("asc");
    }
    setPage(0);
  }

  function toggleSelected(id: string) {
    setSelected((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  }

  function toggleAll() {
    if (!data) return;
    if (selected.size === data.content.length) {
      setSelected(new Set());
    } else {
      setSelected(new Set(data.content.map((r) => r.id)));
    }
  }

  const handleCreate = useCallback(
    (formData: RequirementRequest | UpdateRequirementRequest) => {
      createMutation.mutate(formData as RequirementRequest, {
        onSuccess: () => {
          setCreateOpen(false);
          toast({ title: "Requirement created", variant: "success" });
        },
        onError: (err) => {
          toast({
            title: "Failed to create requirement",
            description: err.message,
            variant: "error",
          });
        },
      });
    },
    [createMutation, toast],
  );

  function handleBulkTransition() {
    if (selected.size === 0) return;
    bulkMutation.mutate(
      { ids: Array.from(selected), status: bulkStatus },
      {
        onSuccess: (result: BulkStatusTransitionResponse) => {
          setSelected(new Set());
          toast({
            title: `Transitioned ${result.totalSucceeded} requirements`,
            description:
              result.totalFailed > 0
                ? `${result.totalFailed} failed`
                : undefined,
            variant: result.totalFailed > 0 ? "error" : "success",
          });
        },
        onError: (err) => {
          toast({
            title: "Bulk transition failed",
            description: err.message,
            variant: "error",
          });
        },
      },
    );
  }

  if (projectLoading || isLoading) return <RequirementsSkeleton />;

  if (!activeProject) {
    return (
      <div className="flex flex-col items-center justify-center gap-4 py-20 text-center">
        <FileText className="h-12 w-12 text-muted-foreground" />
        <h1 className="text-2xl font-semibold">Requirements</h1>
        <p className="text-muted-foreground">
          Select a project to view requirements.
        </p>
      </div>
    );
  }

  const content = data?.content ?? [];
  const totalElements = data?.totalElements ?? 0;
  const totalPages = data?.totalPages ?? 0;

  // Keep panel in sync with query cache so mutations don't show stale data
  const displayedReq = detailReq
    ? (content.find((r) => r.id === detailReq.id) ?? detailReq)
    : null;

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-semibold">Requirements</h1>
        <div className="flex items-center gap-3">
          <span className="text-sm text-muted-foreground">
            {totalElements} total
          </span>
          <button
            type="button"
            className="inline-flex items-center gap-2 rounded-md bg-primary px-3 py-2 text-sm font-medium text-primary-foreground hover:bg-primary/90"
            onClick={() => setCreateOpen(true)}
          >
            <Plus className="h-4 w-4" /> New Requirement
          </button>
        </div>
      </div>

      {/* Filters */}
      <div className="flex flex-wrap items-center gap-3 rounded-lg border border-border bg-card p-3">
        <input
          className="rounded-md border border-input bg-background px-3 py-1.5 text-sm placeholder:text-muted-foreground w-64"
          placeholder="Search by UID or title..."
          value={searchFilter}
          onChange={(e) => {
            setSearchFilter(e.target.value);
            setPage(0);
          }}
        />
        <FilterSelect
          value={statusFilter}
          onChange={(v) => {
            setStatusFilter(v);
            setPage(0);
          }}
          placeholder="Status"
          options={STATUSES}
        />
        <FilterSelect
          value={typeFilter}
          onChange={(v) => {
            setTypeFilter(v);
            setPage(0);
          }}
          placeholder="Type"
          options={REQUIREMENT_TYPES}
        />
        <FilterSelect
          value={priorityFilter}
          onChange={(v) => {
            setPriorityFilter(v);
            setPage(0);
          }}
          placeholder="Priority"
          options={PRIORITIES}
        />
        <input
          type="number"
          className="rounded-md border border-input bg-background px-3 py-1.5 text-sm w-20 placeholder:text-muted-foreground"
          placeholder="Wave"
          value={waveFilter}
          onChange={(e) => {
            setWaveFilter(e.target.value);
            setPage(0);
          }}
          min={0}
        />
        {(statusFilter ||
          typeFilter ||
          priorityFilter ||
          waveFilter ||
          searchFilter) && (
          <button
            type="button"
            className="text-xs text-muted-foreground hover:text-foreground"
            onClick={() => {
              setStatusFilter("");
              setTypeFilter("");
              setPriorityFilter("");
              setWaveFilter("");
              setSearchFilter("");
              setPage(0);
            }}
          >
            Clear filters
          </button>
        )}
      </div>

      {/* Bulk actions */}
      {selected.size > 0 && (
        <div className="flex items-center gap-3 rounded-lg border border-primary/30 bg-primary/5 p-3">
          <span className="text-sm font-medium">{selected.size} selected</span>
          <select
            className="rounded-md border border-input bg-background px-2 py-1 text-sm"
            value={bulkStatus}
            onChange={(e) => setBulkStatus(e.target.value as Status)}
          >
            <option value="ACTIVE">ACTIVE</option>
            <option value="DEPRECATED">DEPRECATED</option>
            <option value="ARCHIVED">ARCHIVED</option>
          </select>
          <button
            type="button"
            className="rounded-md bg-primary px-3 py-1 text-sm text-primary-foreground hover:bg-primary/90"
            onClick={handleBulkTransition}
            disabled={bulkMutation.isPending}
          >
            {bulkMutation.isPending ? "..." : "Transition"}
          </button>
          <button
            type="button"
            className="text-xs text-muted-foreground hover:text-foreground"
            onClick={() => setSelected(new Set())}
          >
            Clear
          </button>
        </div>
      )}

      {/* Table */}
      <div className="rounded-lg border border-border overflow-hidden">
        <table className="w-full text-sm">
          <thead className="bg-card border-b border-border">
            <tr>
              <th className="w-10 px-3 py-3">
                <Checkbox.Root
                  className="flex h-4 w-4 items-center justify-center rounded border border-input bg-background"
                  checked={
                    content.length > 0 && selected.size === content.length
                  }
                  onCheckedChange={toggleAll}
                >
                  <Checkbox.Indicator>
                    <Check className="h-3 w-3" />
                  </Checkbox.Indicator>
                </Checkbox.Root>
              </th>
              <SortableHeader
                field="uid"
                label="UID"
                currentSort={sortField}
                sortDir={sortDir}
                onSort={handleSort}
              />
              <SortableHeader
                field="title"
                label="Title"
                currentSort={sortField}
                sortDir={sortDir}
                onSort={handleSort}
              />
              <SortableHeader
                field="requirementType"
                label="Type"
                currentSort={sortField}
                sortDir={sortDir}
                onSort={handleSort}
              />
              <SortableHeader
                field="priority"
                label="Priority"
                currentSort={sortField}
                sortDir={sortDir}
                onSort={handleSort}
              />
              <SortableHeader
                field="status"
                label="Status"
                currentSort={sortField}
                sortDir={sortDir}
                onSort={handleSort}
              />
              <SortableHeader
                field="wave"
                label="Wave"
                currentSort={sortField}
                sortDir={sortDir}
                onSort={handleSort}
              />
              <SortableHeader
                field="updatedAt"
                label="Updated"
                currentSort={sortField}
                sortDir={sortDir}
                onSort={handleSort}
              />
            </tr>
          </thead>
          <tbody className="divide-y divide-border">
            {content.map((req: RequirementResponse) => (
              <RequirementRow
                key={req.id}
                req={req}
                selected={selected.has(req.id)}
                active={detailReq?.id === req.id}
                onToggle={() => toggleSelected(req.id)}
                onClick={() => setDetailReq(req)}
              />
            ))}
            {content.length === 0 && (
              <tr>
                <td
                  colSpan={8}
                  className="px-4 py-8 text-center text-muted-foreground"
                >
                  No requirements found.
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>

      {/* Pagination */}
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-2">
          <span className="text-sm text-muted-foreground">Rows per page:</span>
          <select
            className="rounded-md border border-input bg-background px-2 py-1 text-sm"
            value={size}
            onChange={(e) => {
              setSize(Number(e.target.value));
              setPage(0);
            }}
          >
            <option value="10">10</option>
            <option value="25">25</option>
            <option value="50">50</option>
          </select>
        </div>
        <div className="flex items-center gap-3">
          <span className="text-sm text-muted-foreground">
            Page {page + 1} of {Math.max(totalPages, 1)}
          </span>
          <button
            type="button"
            className="rounded-md border border-input bg-background px-3 py-1 text-sm hover:bg-accent disabled:opacity-50"
            disabled={page === 0}
            onClick={() => setPage(page - 1)}
          >
            Previous
          </button>
          <button
            type="button"
            className="rounded-md border border-input bg-background px-3 py-1 text-sm hover:bg-accent disabled:opacity-50"
            disabled={page >= totalPages - 1}
            onClick={() => setPage(page + 1)}
          >
            Next
          </button>
        </div>
      </div>

      {/* Create modal */}
      <Modal
        open={createOpen}
        onOpenChange={setCreateOpen}
        title="New Requirement"
        className="max-w-2xl"
      >
        <RequirementForm
          mode="create"
          onSubmit={handleCreate}
          onCancel={() => setCreateOpen(false)}
          loading={createMutation.isPending}
        />
      </Modal>

      {/* Detail slide panel */}
      <SlidePanel
        open={displayedReq !== null}
        onOpenChange={(open) => {
          if (!open) setDetailReq(null);
        }}
        title={
          displayedReq ? `${displayedReq.uid} — ${displayedReq.title}` : ""
        }
      >
        {displayedReq && (
          <RequirementDetailPanel
            key={displayedReq.id}
            requirement={displayedReq}
            onClose={() => setDetailReq(null)}
          />
        )}
      </SlidePanel>
    </div>
  );
}
