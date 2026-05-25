import { AppLayout } from "@/components/layout/app-layout";
import { ProjectProvider } from "@/contexts/project-context";
import { useProjects } from "@/hooks/use-projects";
import { Suspense, lazy } from "react";
import { Link, Navigate, Route, Routes } from "react-router-dom";

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
const TestRunRunner = lazy(() =>
  import("@/pages/test-run-runner").then((m) => ({ default: m.TestRunRunner })),
);

function PageSkeleton() {
  return (
    <div className="flex min-h-[60vh] items-center justify-center">
      <div className="h-8 w-8 animate-spin rounded-full border-4 border-muted border-t-primary" />
    </div>
  );
}

function NotFound() {
  return (
    <div className="py-20 text-center text-muted-foreground">
      <p>Page not found.</p>
      <Link to="/projects" className="mt-4 inline-block text-primary underline">
        Back to projects
      </Link>
    </div>
  );
}

function RootRedirect() {
  const { data: projects = [], isLoading } = useProjects();

  if (isLoading) {
    return (
      <div className="flex min-h-screen items-center justify-center bg-background">
        <div className="h-8 w-8 animate-spin rounded-full border-4 border-muted border-t-primary" />
      </div>
    );
  }

  const first = projects[0];
  if (first) {
    return <Navigate to={`/p/${first.identifier}/`} replace />;
  }

  return <Navigate to="/projects" replace />;
}

export function AppRoutes() {
  return (
    <Suspense fallback={<PageSkeleton />}>
      <Routes>
        <Route path="/" element={<RootRedirect />} />
        <Route element={<AppLayout />}>
          <Route path="projects" element={<Projects />} />
        </Route>
        <Route
          path="p/:projectId"
          element={
            <ProjectProvider>
              <AppLayout />
            </ProjectProvider>
          }
        >
          <Route index element={<Dashboard />} />
          <Route path="requirements" element={<Requirements />} />
          <Route path="requirements/:id" element={<RequirementDetail />} />
          <Route path="test-runs" element={<TestRuns />} />
          <Route path="test-runs/:runId/run" element={<TestRunRunner />} />
          <Route path="graph" element={<Graph />} />
          <Route path="analysis" element={<Analysis />} />
          <Route path="admin" element={<Admin />} />
          <Route path="*" element={<NotFound />} />
        </Route>
        <Route element={<AppLayout />}>
          <Route path="*" element={<NotFound />} />
        </Route>
      </Routes>
    </Suspense>
  );
}
