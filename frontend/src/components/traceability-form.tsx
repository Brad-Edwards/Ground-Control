import {
  FormField,
  inputClass,
  primaryButton,
  secondaryButton,
  selectClass,
} from "@/components/ui/form-field";
import type {
  ArtifactType,
  LinkType,
  TraceabilityLinkRequest,
} from "@/types/api";
import { ARTIFACT_TYPES, LINK_TYPES } from "@/types/api";
import { useState } from "react";

interface TraceabilityFormProps {
  onSubmit: (data: TraceabilityLinkRequest) => void;
  onCancel: () => void;
  loading?: boolean;
}

export function TraceabilityForm({
  onSubmit,
  onCancel,
  loading,
}: TraceabilityFormProps) {
  const [artifactType, setArtifactType] =
    useState<ArtifactType>("GITHUB_ISSUE");
  const [artifactIdentifier, setArtifactIdentifier] = useState("");
  const [artifactUrl, setArtifactUrl] = useState("");
  const [artifactTitle, setArtifactTitle] = useState("");
  const [linkType, setLinkType] = useState<LinkType>("IMPLEMENTS");

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    onSubmit({
      artifactType,
      artifactIdentifier,
      artifactUrl: artifactUrl || undefined,
      artifactTitle: artifactTitle || undefined,
      linkType,
    });
  }

  return (
    <form onSubmit={handleSubmit} className="space-y-4">
      <div className="grid grid-cols-2 gap-4">
        <FormField label="Artifact Type">
          <select
            className={selectClass}
            value={artifactType}
            onChange={(e) => setArtifactType(e.target.value as ArtifactType)}
          >
            {ARTIFACT_TYPES.map((t) => (
              <option key={t} value={t}>
                {t.replace(/_/g, " ")}
              </option>
            ))}
          </select>
        </FormField>
        <FormField label="Link Type">
          <select
            className={selectClass}
            value={linkType}
            onChange={(e) => setLinkType(e.target.value as LinkType)}
          >
            {LINK_TYPES.map((t) => (
              <option key={t} value={t}>
                {t.replace(/_/g, " ")}
              </option>
            ))}
          </select>
        </FormField>
      </div>
      <FormField label="Identifier">
        <input
          className={inputClass}
          value={artifactIdentifier}
          onChange={(e) => setArtifactIdentifier(e.target.value)}
          placeholder="e.g. KeplerOps/Ground-Control#42"
          required
        />
      </FormField>
      <FormField label="URL">
        <input
          className={inputClass}
          value={artifactUrl}
          onChange={(e) => setArtifactUrl(e.target.value)}
          placeholder="https://github.com/..."
        />
      </FormField>
      <FormField label="Title">
        <input
          className={inputClass}
          value={artifactTitle}
          onChange={(e) => setArtifactTitle(e.target.value)}
          placeholder="Artifact title"
        />
      </FormField>
      <div className="flex justify-end gap-3 pt-2">
        <button type="button" className={secondaryButton} onClick={onCancel}>
          Cancel
        </button>
        <button type="submit" className={primaryButton} disabled={loading}>
          {loading ? "Adding..." : "Add Link"}
        </button>
      </div>
    </form>
  );
}
