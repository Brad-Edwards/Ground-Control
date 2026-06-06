#!/usr/bin/env node
import { runInstallWorkflowAssetsCli } from "../../tools/install-workflow-assets.mjs";

await runInstallWorkflowAssetsCli({ defaultPackId: "rust-cargo" });
