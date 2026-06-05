# Repository Guidelines

## Project Structure & Module Organization
This repository contains a Spring Boot backend and a Vue frontend. Backend code lives in `src/main/java/com/lifepilot`, split by domains such as `agent`, `interaction`, `memory`, `workflow`, `mcp`, and `knowledge`. Runtime config, Flyway SQL, built-in workflows, prompts, and skills are under `src/main/resources`. Backend tests live in `src/test/java` and `src/test/resources`.

The frontend lives in `zhiwei-web/`. Put route pages in `src/views`, reusable UI in `src/components`, Pinia stores in `src/stores`, composables in `src/composables`, and API clients in `src/api`. Docs live in `docs/`.

## Agent Harness
This repo uses the AI Coding Harness pattern. Keep this file as the short entry point; reusable harness concepts live under `docs/harness/core/`, while this repository's adapter lives under `docs/harness/adapters/zhiwei/`.

- Canonical project skills live in `.agents/skills/`.
- Codex-discoverable skill wrappers live in `.codex/skills/`.
- Claude Code compatibility assets remain in `.claude/`.
- When working on Java, frontend, Flyway, or Tauri files, read `docs/harness/adapters/zhiwei/rules.md` and the matching rule file before editing.
- Before delivery, follow `docs/harness/adapters/zhiwei/quality-gates.md` or the `ship` skill.

## Build, Test, and Development Commands
Backend: `mvn spring-boot:run` starts the backend, `mvn compile` verifies cross-module compilation, `mvn test` runs JUnit/jqwik tests, and `mvn clean package -DskipTests` builds `target/zhiwei.jar`.

Frontend: `cd zhiwei-web && npm install`, then `npm run dev` for Vite, `npm run build` for production assets, `npm run test:run` for Vitest, and `npm run lint` for ESLint. `docker compose up -d` starts the integrated stack from `.env`.

## Coding Style & Naming Conventions
Java uses 4-space indentation, lowercase packages, and PascalCase types. Prefer Java 22 features used here: `record`, `sealed interface`, pattern matching, and virtual threads where appropriate. Business-tunable values belong in `@ConfigurationProperties` and `application.yml`, not hardcoded constants.

Follow the project's language convention: comments, Javadoc, log messages, exception messages, test names, and commit summaries should be in Chinese; identifiers, config keys, REST paths, and Skill IDs stay in English. Class-level Javadoc should include `@author zsg` and `@since yyyy-MM-dd`. Vue/TypeScript uses 2-space indentation, PascalCase component filenames, and `useX.ts` composables.

## Development Phase Conventions
The project is in active development with no released version and no production user data to protect. Favor a clean end state over backward compatibility: when refactoring, delete old classes, methods, paths, and config keys directly instead of keeping `@Deprecated` shims, dual old/new config keys, or overloads for old signatures. Unmerged Flyway migrations may be rewritten freely; migrations already merged to `develop` still follow the integration checklist.

This is not a license to leave things broken: after any breaking removal, update every call site and affected test so that `mvn compile` and `mvn test` both pass and the Spring context still loads. This exemption ends at the first official release (`beta-release-readiness`), after which backward compatibility applies again. The detailed rule lives in `.kiro/steering/development-conventions.md`.

## Testing Guidelines
Backend tests use JUnit 5, Spring Boot Test, and jqwik. Mock LLM, MCP, and network dependencies in unit tests; use Spring + SQLite for integration tests. Frontend tests use Vitest with `jsdom` and Vue Test Utils; keep specs as `*.spec.ts` beside the component or store they cover. Add regression tests for API, migration, or config changes.

## Commit & Pull Request Guidelines
Work from `develop` using `feature/{name}` or `bugfix/{name}` branches. Commit format is `<type>(<scope>): 中文描述`, with common types such as `feat`, `fix`, `refactor`, `test`, `docs`, and `chore`. Keep each commit focused; avoid vague messages like `update` or `wip`.

PRs should summarize the user-visible change, list verification steps, link the related issue or spec, and include screenshots for `zhiwei-web` UI changes. Call out schema, migration, or config changes explicitly, and never commit `.env`, API keys, or SQLite artifacts. For deeper project rules, see `.kiro/steering/`.
