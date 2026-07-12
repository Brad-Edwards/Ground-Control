.PHONY: rapid build test test-cov test-quality mutation format lint check integration verify policy policy-tests policy-live \
       assert-backup-policy test-backup-restore-local vale-install vale-lint \
       ground-control-mcp-install sync-ground-control-policy scaffold-controller scaffold-audited-entity \
       scaffold-l2-state-machine sync-packs trigger-pack-sync dev clean up down docker-build smoke frontend-install frontend-dev \
       frontend-build frontend-lint frontend-format frontend-test deploy deploy-status deploy-manifest deploy-infra \
       contracts contracts-check contract-breaking mcp-openapi-contract rollback hooks

# --- Rapid dev loop (< 5s) ---

rapid: ## Format + compile, no tests or static analysis
	cd backend && ./gradlew spotlessApply compileJava -Pquick

# --- Standard ---

build: ## Build the project (no tests)
	cd backend && ./gradlew build -x test -Pquick

test: ## Run unit tests (no static analysis)
	cd backend && ./gradlew test -Pquick

test-cov: ## Run tests with coverage report
	cd backend && ./gradlew test jacocoTestReport

test-quality: ## Run Pitest mutation testing (measures test effectiveness; #931)
	cd backend && ./gradlew pitest

mutation: ## Run scoped CLD mutation gate for changed registry boundaries
	python3 tools/mutation/run_boundary_mutation.py

format: ## Format code with Spotless
	cd backend && ./gradlew spotlessApply

lint: ## Check formatting
	cd backend && ./gradlew spotlessCheck

hooks: ## Activate + verify commit-time pre-commit hooks for this clone (ADR-079)
	bash scripts/install-hooks.sh

# --- Full verification (CI-equivalent) ---

check: ## Full build + tests + static analysis + coverage
	cd backend && ./gradlew check

integration: ## Integration tests (Testcontainers)
	cd backend && ./gradlew integrationTest

verify: ## Full CI-equivalent verification
	cd backend && ./gradlew check integrationTest openjmlEsc

policy-tests: ## Run unit tests for repo policy tooling
	python3 -m unittest discover -s tools/tests -p 'test_*.py'

contracts: ## Regenerate committed contract artifacts (OpenAPI + generated TypeScript)
	cd backend && ./gradlew generateContractOpenApi
	node tools/contracts/generate-contracts.mjs

contracts-check: contracts ## Fail if regenerated contract artifacts differ from committed files
	git diff --exit-code contracts/ frontend/src/types/api.ts

contract-breaking: ## Check OpenAPI breaking changes against BASE_REF (default origin/dev)
	node tools/contracts/check-breaking-changes.mjs

mcp-openapi-contract: contracts ## MCP↔backend write-contract drift gate (ADR-034/#1106, ADR-082/#1275)
	GC_OPENAPI_SPEC=contracts/openapi/openapi.json node --test mcp/ground-control/openapi-contract.test.js

vale-install: ## Install Vale prose linter (tools/install-vale.sh → .tools/vale/)
	bash tools/install-vale.sh

# BASE_REF defaults to origin/dev for local invocation. CI sets it to
# origin/<base-branch> for the current pull_request event.
vale-lint: vale-install ## Run Vale on .md docs touched in the diff vs BASE_REF (default origin/dev)
	@BASE_REF="$${BASE_REF:-origin/dev}"; \
	CHANGED_DOCS=$$(git diff --name-only --diff-filter=ACMR $$BASE_REF...HEAD 2>/dev/null | grep -E '\.(md|markdown)$$' || true); \
	if [ -z "$$CHANGED_DOCS" ]; then \
	  echo "vale-lint: no changed docs vs $$BASE_REF"; \
	  exit 0; \
	fi; \
	if [ ! -x .tools/vale/current/vale ]; then \
	  echo "vale-lint: Vale not installed at .tools/vale/current/vale; run 'make vale-install'" >&2; \
	  exit 1; \
	fi; \
	.tools/vale/current/vale --config=.vale.ini $$CHANGED_DOCS

policy: policy-tests assert-backup-policy vale-lint ## Run repo-native policy checks shared by Claude and Codex
	@BASE_REF="$${BASE_REF:-origin/dev}"; \
	python3 bin/policy --base "$$BASE_REF" --skip-pr-body

assert-backup-policy: ## Assert GC-P021 backup cadence / retention / verification defaults are intact
	bash scripts/assert-backup-policy.sh

implement-cost-summary: ## Summarize /implement step telemetry — wall time + token counts (when available) per step / per model (ADR-036)
	python3 tools/summarize_implement_telemetry.py

test-backup-restore-local: ## Run the self-contained local backup/restore verification loop (requires Docker)
	bash scripts/test-backup-restore-locally.sh

ground-control-mcp-install: ## Install dependencies for the repo-local Ground Control MCP helpers
	npm --prefix mcp/ground-control ci

policy-live: ground-control-mcp-install ## Run live Ground Control policy checks (requires GC_BASE_URL)
	node tools/ground_control/check_adr_drift.mjs
	node tools/ground_control/check_live_policy.mjs

sync-ground-control-policy: ground-control-mcp-install ## Sync repo policy expectations into Ground Control
	node tools/ground_control/sync_policy.mjs --apply

sync-packs: ## Import and install cataloged packs into Ground Control (requires GC_BASE_URL and pack-registry token)
	node tools/packs/sync_packs.mjs

trigger-pack-sync: ## Dispatch the remote pack sync workflow (PROJECT=<id> PACK_IDS=id1,id2 REF=<branch>)
	./scripts/pack-sync.sh $(if $(PROJECT),--project $(PROJECT),) $(if $(PACK_IDS),--pack-ids $(PACK_IDS),) $(if $(REF),--ref $(REF),)

scaffold-controller: ## Create a controller + WebMvcTest scaffold (NAME=Foo FEATURE=bar)
	python3 bin/scaffold-controller "$(FEATURE)" "$(NAME)"

scaffold-audited-entity: ## Create an audited entity scaffold (NAME=Foo AREA=bar)
	python3 bin/scaffold-audited-entity "$(AREA)" "$(NAME)"

scaffold-l2-state-machine: ## Create an L2 state-machine scaffold (NAME=Foo AREA=bar)
	python3 bin/scaffold-l2-state-machine "$(AREA)" "$(NAME)"

# --- Frontend ---

frontend-install: ## Install frontend dependencies
	cd frontend && npm install

frontend-dev: ## Start frontend dev server (Vite)
	cd frontend && npm run dev

frontend-build: ## Build frontend for production
	cd frontend && npm run build

frontend-lint: ## Lint frontend code (Biome)
	cd frontend && npm run lint

frontend-format: ## Format frontend code (Biome)
	cd frontend && npm run format

frontend-test: ## Run frontend unit tests (Vitest)
	cd frontend && npm test

# --- Infrastructure ---

dev: ## Start development server (loads .env)
	set -a && [ -f .env ] && . ./.env && set +a && cd backend && ./gradlew bootRun

up: ## Start Docker Compose services (PostgreSQL, Redis)
	docker compose up -d

down: ## Stop Docker Compose services
	docker compose down

docker-build: ## Build Docker image (frontend + backend)
	docker build -f backend/Dockerfile -t ghcr.io/autarchy-ai/ground-control:latest .

smoke: docker-build ## Build Docker image and verify Flyway + health
	@echo "Starting smoke test..."
	@docker rm -f gc-smoke-db gc-smoke 2>/dev/null || true
	@docker run -d --name gc-smoke-db \
		-e POSTGRES_DB=ground_control \
		-e POSTGRES_USER=gc \
		-e POSTGRES_PASSWORD=gc \
		-p 5433:5432 \
		--health-cmd "pg_isready -U gc -d ground_control" \
		--health-interval 2s --health-timeout 5s --health-retries 10 \
		postgres:16
	@echo "Waiting for database..."
	@for i in $$(seq 1 30); do \
		docker inspect --format='{{.State.Health.Status}}' gc-smoke-db 2>/dev/null | grep -q healthy && break; \
		sleep 1; \
	done
	@docker run -d --name gc-smoke \
		--network host \
		-e GC_DATABASE_URL=jdbc:postgresql://localhost:5433/ground_control \
		-e GC_DATABASE_USER=gc \
		-e GC_DATABASE_PASSWORD=gc \
		ghcr.io/autarchy-ai/ground-control:latest
	@echo "Waiting for application startup..."
	@PASS=false; for i in $$(seq 1 60); do \
		HEALTH=$$(curl -sf http://localhost:8000/actuator/health 2>/dev/null) && { \
			echo "Smoke test passed: $$HEALTH"; PASS=true; break; \
		}; \
		sleep 2; \
	done; \
	if [ "$$PASS" != "true" ]; then \
		echo "Smoke test failed after 120s"; \
		docker logs gc-smoke; \
	fi; \
	docker rm -f gc-smoke-db gc-smoke 2>/dev/null || true; \
	[ "$$PASS" = "true" ]

# --- Deployment (ADR-030: on-prem red-dragon) ---

deploy: ## Manual deploy to red-dragon (syncs artifacts, validates env, rolls out with rollback, publishes deploy state)
	./scripts/deploy.sh

deploy-status: ## Show the latest published production GitHub Deployment (GC-P023) — answers "what's deployed?" without SSH
	@dep=$$(gh api "repos/{owner}/{repo}/deployments?environment=production&per_page=1" --jq '.[0].id' 2>/dev/null); \
	if [ -z "$$dep" ] || [ "$$dep" = "null" ]; then \
		echo "No production GitHub Deployments found (or gh not authenticated)."; \
	else \
		gh api "repos/{owner}/{repo}/deployments/$$dep" --jq '"deployment \(.id)  ref \(.sha[0:12])  created \(.created_at)  by \(.creator.login)"'; \
		gh api "repos/{owner}/{repo}/deployments/$$dep/statuses?per_page=1" --jq '.[0] | "  state: \(.state)  \(.description // "")"'; \
	fi

deploy-manifest: ## Regenerate deploy/docker/MANIFEST.sha256 after editing any canonical deploy artifact (GC-P023)
	cd deploy/docker && sha256sum deploy.sh docker-compose.prod.yml validate-env.sh env.schema > MANIFEST.sha256
	@echo "Regenerated deploy/docker/MANIFEST.sha256"

rollback: ## Roll production back to a prior version (make rollback VERSION=<x.y.z|digest>)
	@[ -n "$$VERSION" ] || { echo "ERROR: VERSION is required, e.g. make rollback VERSION=1.0.1"; exit 1; }
	./scripts/rollback.sh "$$VERSION"

clean: ## Remove build artifacts
	cd backend && ./gradlew clean

help: ## Show this help
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | sort | \
		awk 'BEGIN {FS = ":.*?## "}; {printf "\033[36m%-15s\033[0m %s\n", $$1, $$2}'

.DEFAULT_GOAL := help
