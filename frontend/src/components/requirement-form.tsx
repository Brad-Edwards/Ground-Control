import {
  FormField,
  inputClass,
  primaryButton,
  secondaryButton,
  selectClass,
} from "@/components/ui/form-field";
import type {
  Priority,
  RequirementRequest,
  RequirementResponse,
  RequirementType,
  UpdateRequirementRequest,
} from "@/types/api";
import { PRIORITIES, REQUIREMENT_TYPES } from "@/types/api";
import { useState } from "react";

interface RequirementFormProps {
  initial?: RequirementResponse;
  onSubmit: (data: RequirementRequest | UpdateRequirementRequest) => void;
  onCancel: () => void;
  loading?: boolean;
  mode: "create" | "edit";
}

export function RequirementForm({
  initial,
  onSubmit,
  onCancel,
  loading,
  mode,
}: RequirementFormProps) {
  const [uid, setUid] = useState(initial?.uid ?? "");
  const [title, setTitle] = useState(initial?.title ?? "");
  const [statement, setStatement] = useState(initial?.statement ?? "");
  const [rationale, setRationale] = useState(initial?.rationale ?? "");
  const [requirementType, setRequirementType] = useState<RequirementType>(
    initial?.requirementType ?? "FUNCTIONAL",
  );
  const [priority, setPriority] = useState<Priority>(
    initial?.priority ?? "SHOULD",
  );
  const [wave, setWave] = useState(String(initial?.wave ?? 1));
  const [error, setError] = useState<string | null>(null);

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    // Backend RequirementRequest/UpdateRequirementRequest annotate uid/title/
    // statement @NotBlank, so whitespace-only values are rejected server-side.
    // HTML `required` only catches the empty string, so check trimmed values
    // here, show an inline error rather than failing silently, and submit
    // trimmed values.
    const trimmedUid = uid.trim();
    const trimmedTitle = title.trim();
    const trimmedStatement = statement.trim();
    const trimmedRationale = rationale.trim();
    const missing: string[] = [];
    if (mode === "create" && !trimmedUid) missing.push("UID");
    if (!trimmedTitle) missing.push("title");
    if (!trimmedStatement) missing.push("statement");
    if (missing.length > 0) {
      setError(`${missing.join(", ")} must not be blank.`);
      return;
    }
    setError(null);
    if (mode === "create") {
      const data: RequirementRequest = {
        uid: trimmedUid,
        title: trimmedTitle,
        statement: trimmedStatement,
        rationale: trimmedRationale || undefined,
        requirementType,
        priority,
        wave: Number(wave) || undefined,
      };
      onSubmit(data);
    } else {
      const data: UpdateRequirementRequest = {
        title: trimmedTitle,
        statement: trimmedStatement,
        rationale: trimmedRationale || undefined,
        requirementType,
        priority,
        wave: Number(wave) || undefined,
      };
      onSubmit(data);
    }
  }

  return (
    <form onSubmit={handleSubmit} className="space-y-4">
      {mode === "create" && (
        <FormField label="UID">
          <input
            className={inputClass}
            value={uid}
            onChange={(e) => setUid(e.target.value)}
            placeholder="GC-A001"
            required
          />
        </FormField>
      )}
      <FormField label="Title">
        <input
          className={inputClass}
          value={title}
          onChange={(e) => setTitle(e.target.value)}
          placeholder="Requirement title"
          required
        />
      </FormField>
      <FormField label="Statement">
        <textarea
          className={`${inputClass} min-h-[80px] resize-y`}
          value={statement}
          onChange={(e) => setStatement(e.target.value)}
          placeholder="The system shall..."
          rows={3}
          required
        />
      </FormField>
      <FormField label="Rationale">
        <textarea
          className={`${inputClass} min-h-[60px] resize-y`}
          value={rationale}
          onChange={(e) => setRationale(e.target.value)}
          placeholder="Why this requirement exists..."
          rows={2}
        />
      </FormField>
      <div className="grid grid-cols-3 gap-4">
        <FormField label="Type">
          <select
            className={selectClass}
            value={requirementType}
            onChange={(e) =>
              setRequirementType(e.target.value as RequirementType)
            }
          >
            {REQUIREMENT_TYPES.map((t) => (
              <option key={t} value={t}>
                {t.replace("_", " ")}
              </option>
            ))}
          </select>
        </FormField>
        <FormField label="Priority">
          <select
            className={selectClass}
            value={priority}
            onChange={(e) => setPriority(e.target.value as Priority)}
          >
            {PRIORITIES.map((p) => (
              <option key={p} value={p}>
                {p}
              </option>
            ))}
          </select>
        </FormField>
        <FormField label="Wave">
          <input
            type="number"
            className={inputClass}
            value={wave}
            onChange={(e) => setWave(e.target.value)}
            min={0}
          />
        </FormField>
      </div>
      {error && (
        <p className="text-sm text-red-400" role="alert">
          {error}
        </p>
      )}
      <div className="flex justify-end gap-3 pt-2">
        <button type="button" className={secondaryButton} onClick={onCancel}>
          Cancel
        </button>
        <button type="submit" className={primaryButton} disabled={loading}>
          {loading ? "Saving..." : mode === "create" ? "Create" : "Save"}
        </button>
      </div>
    </form>
  );
}
