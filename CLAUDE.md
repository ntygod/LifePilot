# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

ZhiWei（知微）is a **local personal assistant** for ordinary users — explicitly not a developer tool. Spring Boot backend + Vue 3 SPA frontend.

Positioning (from `docs/planned/memory-and-proactive-evolution-gaps.md`, treat as authoritative): users only perceive three things — **更少误打扰 / 更准记住 / 更懂何时该开口**. All optimization must hold on a single machine, with local LLM optional and a strict token budget.

Deliberately out of scope: code execution, shell, and git tooling (vendor coding agents are stronger there, and none of it serves the three goals above). `lifepilot.sandbox.enabled` and `lifepilot.meta.infra.git.enabled` both default to `false`.

Capabilities: multi-layer memory, event-driven proactivity, cron tasks, workflow automation, multi-channel messaging (WeChat Work, DingTalk, Feishu), and an extension marketplace.

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
```

### Docker
```bash
cp .env.example .env   # Configure API keys and ports first
docker compose up -d   # Start full stack
```

## Architecture

The system is layered: **Interaction → Gateway → Agent Engine → Capability Layer → Knowledge/Memory → Infrastructure → Storage**.

- **Interaction**: Web UI (Vue 3 SSE), Tauri 2.x desktop app, Channel adapters (Feishu/DingTalk/WeChat Work)
- **Gateway**: `MessageGateway` with 6-stage middleware pipeline (Auth → RateLimit → Security → Router → Execution → Audit)
- **Agent Engine**: `ReactAgentLoop` — ReAct-style control loop with suspend/resume, retry, and breakpoint recovery
- **Capability Layer**: Skill system, Tool system, MCP protocol, Workflow engine, Datastore（code sandbox 默认关闭）
- **Memory**: 4-layer memory (Working L1 / Episodic L2 / Semantic L3 / Procedural L4) with consolidation pipeline
- **Storage**: SQLite with WAL mode, FTS5 full-text index, sqlite-vec for vector storage (`~/.zhiwei/zhiwei.db`)

Key source files:
- `src/main/java/com/lifepilot/agent/ReactAgentLoop.java` — core agent loop
- `src/main/java/com/lifepilot/agent/task/CronScheduler.java` — cron 定时任务调度（含 `runOnce` 立即执行）
- `src/main/java/com/lifepilot/project/service/ProjectService.java` — project CRUD + 级联删除
- `src/main/java/com/lifepilot/interaction/web/controller/ScheduledTaskController.java` — 定时任务全局管理 REST
- `src/main/java/com/lifepilot/generation/router/GenerationRouter.java` — text generation routing with circuit breaker
- `src/main/java/com/lifepilot/embedding/router/EmbeddingRouter.java` — embedding routing
- `src/main/java/com/lifepilot/rerank/router/RerankRouter.java` — rerank routing
- `src/main/resources/application.yml` — all runtime configuration
- `src/main/resources/db/migration/` — Flyway migration scripts; run `scripts/harness/Get-FlywayLatest.ps1` before creating the next version
- `src/main/resources/prompts/` — StringTemplate prompt files
- `src/main/resources/skills/` — preset skill definitions (13 skills; 删改此目录后必须 `mvn clean`，否则 `target/classes/skills/` 残留旧副本会让技能相关测试读到陈旧 classpath 数据)
- `src/main/java/com/lifepilot/agent/initiative/` — 事件驱动主动引擎（Signal → Thinker → ThoughtPool → Gatekeeper → ConversationInitiator）；旧的心跳轮询提醒栈已删除
- `zhiwei-web/src-tauri/` — Tauri 2.x desktop app (Rust)

## Agent Harness

The reusable AI Coding Harness core is in `docs/harness/core/`. This repository's adapter is in `docs/harness/adapters/zhiwei/`. Claude Code compatibility files under `.claude/` are still available, but new or corrected reusable rules should go to the core, while project facts should go to the adapter.

Key harness files:
- `docs/harness/README.md` — harness map
- `docs/harness/core/` — reusable AI coding harness pattern
- `docs/harness/adapters/zhiwei/` — this project adapter
- `docs/harness/rules/` — current compatibility path for Java, frontend, database, and Tauri rules
- `docs/harness/quality-gates.md` — current compatibility path for delivery checks
- `.agents/skills/` — canonical project skills
- `.codex/skills/` — Codex skill entrypoints

## Coding Conventions

Detailed conventions are indexed by `docs/harness/adapters/zhiwei/rules.md`, with rule bodies currently in `docs/harness/rules/` and mirrored in `.claude/rules/` for Claude Code:
- **Java**: Java 22 features required, Chinese comments/logs, `record` over Lombok — see `docs/harness/rules/java.md`
- **Frontend**: Reka UI 2.x, Tailwind project scales, stable layouts — see `docs/harness/rules/frontend.md`
- **Database**: Flyway naming, SQLite dialect, parameterized queries — see `docs/harness/rules/database.md`
- **Tauri/Rust**: Tauri 2.x API, Chinese comments, `Result` + `?` error handling — see `docs/harness/rules/tauri.md`

## Common Workflows

1. **新增 API 端点**: 在对应包下创建 `@RestController` → 编写 Service → 添加测试 → 如需建表则新建 Flyway 迁移 `V{n+1}__desc.sql`
2. **新增 Flyway 迁移**: 运行 `scripts/harness/Get-FlywayLatest.ps1` → 创建 `V{n+1}__{description}.sql` → `mvn compile` 验证
3. **新增工具/技能**: 在 `tool/` 或 `skill/` 包下实现 → 注册到对应 Registry → 添加测试
4. **前端新页面**: `src/views/` 下创建页面组件 → 添加路由 → 使用 Reka UI 组件 + Tailwind 命名尺度

## Troubleshooting

- **SQLite 锁**: WAL 模式下只允许单写入者，长事务会阻塞其他写入，保持事务短小
- **Flyway 校验失败**: 已有迁移文件被修改会导致 checksum 不匹配，永远不要修改已执行的迁移
- **端口 8080 占用**: `netstat -ano | findstr :8080` 找到进程，或修改 `application.yml` 中的 `server.port`

## Git Conventions

- Base branch: `develop`; feature branches: `feature/{name}`; bugfix branches: `bugfix/{name}`
- Commit format: `<type>(<scope>): 中文描述` — types: `feat`, `fix`, `refactor`, `test`, `docs`, `chore`
- PRs must list verification steps and call out any schema/migration/config changes

## Key Docs

- `docs/ARCHITECTURE.md` — system architecture overview
- `docs/API_STANDARD.md` — API design standard
- `docs/architecture/` — 32 detailed module design docs
- `docs/guides/` — usage guides (workflow, Feishu integration)
