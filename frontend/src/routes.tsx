import { AppShell } from "@/components/layout/app-shell";
import { EmptyState } from "@/components/ui/empty-state";
import { LoadingState } from "@/components/ui/loading-state";
import { ProjectProvider } from "@/contexts/project-context";
import { useProjects } from "@/hooks/use-projects";
import { FileQuestion } from "lucide-react";
import { Suspense, lazy } from "react";
import { Link, Navigate, Route, Routes } from "react-router";

const Admin = lazy(() =>
  import("@/pages/admin").then((m) => ({ default: m.Admin })),
);
const Analysis = lazy(() =>
  import("@/pages/analysis").then((m) => ({ default: m.Analysis })),
);
const Dashboard = lazy(() =>
  import("@/pages/dashboard").then((m) => ({ default: m.Dashboard })),
);
const Graph = lazy(() =>
  import("@/pages/graph").then((m) => ({ default: m.Graph })),
);
const Projects = lazy(() =>
  import("@/pages/projects").then((m) => ({ default: m.Projects })),
);
const RequirementDetail = lazy(() =>
  import("@/pages/requirement-detail").then((m) => ({
    default: m.RequirementDetail,
  })),
);
const Requirements = lazy(() =>
  import("@/pages/requirements").then((m) => ({ default: m.Requirements })),
);
const TestRuns = lazy(() =>
  import("@/pages/test-runs").then((m) => ({ default: m.TestRuns })),
);
const TraceabilityMatrix = lazy(() =>
  import("@/pages/traceability-matrix").then((m) => ({
    default: m.TraceabilityMatrix,
  })),
);
const TestRunRunner = lazy(() =>
  import("@/pages/test-run-runner").then((m) => ({ default: m.TestRunRunner })),
);
const WorkflowRuns = lazy(() =>
  import("@/pages/workflow-runs").then((m) => ({
    default: m.WorkflowRuns,
  })),
);

function NotFound() {
  return (
    <EmptyState
      icon={FileQuestion}
      title="Page not found"
      description="The page you are looking for does not exist."
      action={
        <Link to="/projects" className="text-sm text-primary underline">
          Back to projects
        </Link>
      }
    />
  );
}

function RootRedirect() {
  const { data: projects = [], isLoading } = useProjects();

  if (isLoading) {
    return <LoadingState className="min-h-screen" />;
  }

  const first = projects[0];
  if (first) {
    return <Navigate to={`/p/${first.identifier}/`} replace />;
  }

  return <Navigate to="/projects" replace />;
}

export function AppRoutes() {
  return (
    <Suspense fallback={<LoadingState />}>
      <Routes>
        <Route path="/" element={<RootRedirect />} />
        <Route element={<AppShell />}>
          <Route path="projects" element={<Projects />} />
        </Route>
        <Route
          path="p/:projectId"
          element={
            <ProjectProvider>
              <AppShell />
            </ProjectProvider>
          }
        >
          <Route index element={<Dashboard />} />
          <Route path="requirements" element={<Requirements />} />
          <Route path="requirements/:id" element={<RequirementDetail />} />
          <Route path="traceability-matrix" element={<TraceabilityMatrix />} />
          <Route path="test-runs" element={<TestRuns />} />
          <Route path="test-runs/:runId/run" element={<TestRunRunner />} />
          <Route path="graph" element={<Graph />} />
          <Route path="analysis" element={<Analysis />} />
          <Route path="workflow-runs" element={<WorkflowRuns />} />
          <Route path="admin" element={<Admin />} />
          <Route path="*" element={<NotFound />} />
        </Route>
        <Route element={<AppShell />}>
          <Route path="*" element={<NotFound />} />
        </Route>
      </Routes>
    </Suspense>
  );
}
