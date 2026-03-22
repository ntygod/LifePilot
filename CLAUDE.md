# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

ZhiWei（知微）is a self-hosted AI Agent system with a Spring Boot backend and Vue 3 SPA frontend. It supports multi-layer memory, autonomous task execution, workflow automation, multi-channel messaging (WeChat Work, DingTalk, Feishu), and a plugin marketplace.

## Build & Development Commands

### Backend
```bash
mvn spring-boot:run              # Start dev server (port 8080)
mvn compile                      # Verify compilation
mvn test                         # Run JUnit 5 + jqwik tests
mvn test -Dtest=ClassName        # Run a single test class
mvn clean package -DskipTests    # Build target/zhiwei.jar
```

### Frontend
```bash
cd zhiwei-web
npm install
npm run dev          # Vite dev server (port 5173)
npm run build        # Production build → dist/
npm run test:run     # Vitest single run
npm run lint         # ESLint
```

### Docker
```bash
cp .env.example .env   # Configure API keys and ports first
docker compose up -d   # Start full stack
```

## Architecture

The system is layered: **Interaction → Gateway → Agent Engine → Capability Layer → Knowledge/Memory → Infrastructure → Storage**.

- **Interaction**: Web UI (Vue 3 SSE), Channel adapters (Feishu/DingTalk/WeChat Work)
- **Gateway**: `MessageGateway` with 6-stage middleware pipeline (Auth → RateLimit → Security → Router → Execution → Audit)
- **Agent Engine**: `ReactAgentLoop` — ReAct-style control loop with suspend/resume, retry, and breakpoint recovery
- **Capability Layer**: Skill system, Tool system, MCP protocol, Workflow engine, Code sandbox, Datastore
- **Memory**: 4-layer memory (Working L1 / Episodic L2 / Semantic L3 / Procedural L4) with consolidation pipeline
- **Storage**: SQLite with WAL mode, FTS5 full-text index, sqlite-vec for vector storage (`~/.zhiwei/zhiwei.db`)

Key source files:
- `src/main/java/com/lifepilot/agent/ReactAgentLoop.java` — core agent loop
- `src/main/java/com/lifepilot/llm/LlmRouter.java` — multi-model routing with circuit breaker
- `src/main/resources/application.yml` — all runtime configuration
- `src/main/resources/db/migration/` — Flyway migration scripts (naming: `V{n}__{desc}.sql`)
- `src/main/resources/prompts/` — StringTemplate prompt files
- `src/main/resources/skills/` — built-in skill definitions

## Coding Conventions

### Java
- **Java 22 features are required**: use `record` for DTOs, `sealed interface` for state machines, pattern matching for switch, virtual threads for I/O-heavy operations
- **No Lombok `@Data`** — use `record` or explicit accessors
- All comments, Javadoc, log messages, exception messages, and test method names must be in **Chinese**
- Identifiers, config keys, REST paths, and Skill IDs stay in English
- Class-level Javadoc must include `@author zsg` and `@since yyyy-MM-dd`
- Log levels: `ERROR` for intervention-needed failures, `WARN` for recoverable issues, `INFO` for key business flows, `DEBUG` for dev tracing
- API responses use `ApiResponse<T>` with `{ code, message, data }` structure; paginated responses use `{ items, total, page, pageSize }`
- Business-tunable values go in `@ConfigurationProperties` + `application.yml`, not hardcoded

### Frontend
- UI library is **Reka UI 2.x** — not shadcn-vue
- Use `<script setup lang="ts">` with typed `defineProps` / `defineEmits`
- Tailwind spacing uses named scales only: `xs`(4px) `sm`(8px) `md`(16px) `lg`(24px) `xl`(32px) `2xl`(48px) — no arbitrary values like `p-3` or `px-5`
- State management via Pinia stores in `src/stores/`; composables in `src/composables/useX.ts`
- Component filenames: PascalCase; route pages in `src/views/`

### Database
- Flyway scripts in `src/main/resources/db/migration/`, named `V{n}__{description}.sql`
- SQL keywords uppercase; table/column names snake_case; no `SELECT *`; always use parameterized queries

## Git Conventions

- Base branch: `develop`; feature branches: `feature/{name}`; bugfix branches: `bugfix/{name}`
- Commit format: `<type>(<scope>): 中文描述` — types: `feat`, `fix`, `refactor`, `test`, `docs`, `chore`
- PRs must list verification steps and call out any schema/migration/config changes

## Key Docs

- `docs/ARCHITECTURE.md` — system architecture overview
- `docs/API_STANDARD.md` — API design standard
- `docs/architecture/` — 38 detailed module design docs
- `docs/guides/` — usage guides (workflow, Feishu integration)
