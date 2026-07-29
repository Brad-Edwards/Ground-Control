// @vitest-environment jsdom
import { ToastProvider } from "@/components/ui/toast";
import { ProjectProvider } from "@/contexts/project-context";
import type { SessionResponse } from "@/hooks/use-session";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router";
import { describe, expect, it } from "vitest";
import { AppShell } from "../app-shell";

function renderShell(session: SessionResponse) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  qc.setQueryData(["session"], session);
  qc.setQueryData(
    ["projects"],
    [
      {
        id: "1",
        identifier: "demo",
        name: "Demo Project",
        description: "",
        createdAt: "",
        updatedAt: "",
      },
    ],
  );
  return render(
    <QueryClientProvider client={qc}>
      <ToastProvider>
        <MemoryRouter initialEntries={["/p/demo/requirements"]}>
          <Routes>
            <Route
              path="/p/:projectId"
              element={
                <ProjectProvider>
                  <AppShell />
                </ProjectProvider>
              }
            >
              <Route
                path="requirements"
                element={<div>requirements body</div>}
              />
            </Route>
          </Routes>
        </MemoryRouter>
      </ToastProvider>
    </QueryClientProvider>,
  );
}

describe("AppShell", () => {
  it("renders grouped navigation and the current route body", () => {
    renderShell({
      displayName: "alice",
      roles: ["ROLE_USER"],
      canAdminister: false,
    });
    expect(screen.getByText("Overview")).toBeDefined();
    expect(screen.getByText("Graph & Analysis")).toBeDefined();
    expect(screen.getByText("Workflow")).toBeDefined();
    expect(screen.getByText("requirements body")).toBeDefined();
  });

  it("hides the Admin group for a principal without the admin capability", () => {
    renderShell({
      displayName: "alice",
      roles: ["ROLE_USER"],
      canAdminister: false,
    });
    expect(screen.queryByText("Administration")).toBeNull();
    expect(screen.queryByText("Admin")).toBeNull();
  });

  it("shows the Admin group when the server reports canAdminister", () => {
    renderShell({
      displayName: "root",
      roles: ["ROLE_ADMIN"],
      canAdminister: true,
    });
    expect(screen.getByText("Administration")).toBeDefined();
    expect(screen.getByText("Admin")).toBeDefined();
  });

  it("exposes the mobile navigation trigger", () => {
    renderShell({
      displayName: "alice",
      roles: ["ROLE_USER"],
      canAdminister: false,
    });
    expect(screen.getByLabelText("Open navigation menu")).toBeDefined();
  });
});
