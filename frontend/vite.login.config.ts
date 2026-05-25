import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import tailwindcss from "@tailwindcss/vite";
import path from "node:path";

// Standalone build for the login bundle. The main app (vite.config.ts) outputs to
// /assets/ and is gated by the path matrix in BrowserSecurityConfig per ADR-037
// §2 — fetching the main bundle anonymously would leak the application's UI
// surface area (route names, component shapes, role-gated conditionals). The
// login bundle outputs to /login-assets/ and is anonymously fetchable because the
// React login screen must render to a browser that has no session yet.
//
// The two configs share no Rollup graph (two separate `vite build` invocations).
// React is duplicated across both bundles by design; the security boundary
// outweighs the ~150 KB overhead.
export default defineConfig({
  plugins: [react(), tailwindcss()],
  resolve: {
    alias: {
      "@": path.resolve(__dirname, "./src"),
    },
  },
  build: {
    outDir: "dist",
    // The main build owns clearing dist/; this build must preserve /assets/ and
    // /index.html that the prior step produced.
    emptyOutDir: false,
    rollupOptions: {
      input: {
        login: path.resolve(__dirname, "login.html"),
      },
      output: {
        entryFileNames: "login-assets/[name]-[hash].js",
        chunkFileNames: "login-assets/[name]-[hash].js",
        assetFileNames: "login-assets/[name]-[hash][extname]",
      },
    },
  },
});
