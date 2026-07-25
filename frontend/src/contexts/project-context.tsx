import { useToast } from "@/components/ui/toast";
import type { ProjectResponse } from "@/hooks/use-projects";
import { useProjects } from "@/hooks/use-projects";
import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
} from "react";
import { useNavigate, useParams } from "react-router";

interface ProjectContextValue {
  projects: ProjectResponse[];
  activeProject: ProjectResponse | null;
  setActiveProject: (project: ProjectResponse) => void;
  isLoading: boolean;
}

const ProjectContext = createContext<ProjectContextValue | null>(null);

export function ProjectProvider({ children }: { children: React.ReactNode }) {
  const { projectId } = useParams<{ projectId: string }>();
  const navigate = useNavigate();
  const { toast } = useToast();
  const { data: projects = [], isLoading } = useProjects();

  const activeProject = useMemo(
    () => projects.find((p) => p.identifier === projectId) ?? null,
    [projects, projectId],
  );

  // toast is referentially stable (useCallback in ToastProvider)
  useEffect(() => {
    if (!isLoading && projects.length > 0 && projectId && !activeProject) {
      toast({ title: "Project not found", variant: "error" });
      navigate("/projects", { replace: true });
    }
  }, [isLoading, projects, projectId, activeProject, navigate, toast]);

  const setActiveProject = useCallback(
    (project: ProjectResponse) => {
      navigate(`/p/${project.identifier}/`);
    },
    [navigate],
  );

  const value = useMemo(
    () => ({
      projects,
      activeProject,
      setActiveProject,
      isLoading,
    }),
    [projects, activeProject, setActiveProject, isLoading],
  );

  return (
    <ProjectContext.Provider value={value}>{children}</ProjectContext.Provider>
  );
}

export function useProjectContext() {
  const context = useContext(ProjectContext);
  if (!context) {
    throw new Error("useProjectContext must be used within a ProjectProvider");
  }
  return context;
}
