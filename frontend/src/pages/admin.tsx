import { useProjectContext } from "@/contexts/project-context";
import { Settings } from "lucide-react";
import {
  GitHubIssueCreation,
  GraphMaterialization,
} from "./admin/git-hub-issue-creation";
import {
  GitHubSync,
  PackRegistryImport,
  StrictDocImport,
} from "./admin/pack-registry-import";

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
