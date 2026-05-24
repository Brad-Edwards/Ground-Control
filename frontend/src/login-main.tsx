import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { Login } from "./login-app/login";
import "./main.css";

const root = document.getElementById("root");
if (!root) throw new Error("Root element not found");

createRoot(root).render(
  <StrictMode>
    <Login />
  </StrictMode>,
);
