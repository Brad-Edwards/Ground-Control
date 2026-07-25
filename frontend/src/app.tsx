import { ToastProvider } from "@/components/ui/toast";
import { queryClient } from "@/lib/query-client";
import { AppRoutes } from "@/routes";
import { QueryClientProvider } from "@tanstack/react-query";
import { BrowserRouter } from "react-router";

export function App() {
  return (
    <QueryClientProvider client={queryClient}>
      <ToastProvider>
        <BrowserRouter>
          <AppRoutes />
        </BrowserRouter>
      </ToastProvider>
    </QueryClientProvider>
  );
}
