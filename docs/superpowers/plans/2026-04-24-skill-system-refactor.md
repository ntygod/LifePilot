# Skill 系统重构 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> **前置依赖**：`feature/tool-exposure-refactor` 分支已完成工具暴露重构（Tier 1 / ToolSearch / ToolValidator 已就绪）。本计划在同一分支/worktree 上继续。
> **工作目录**：`D:\WorkSpace\Project\News\.worktrees\tool-exposure-refactor`

**Goal：** 把 ZhiWei 的初级 Skill 系统升级为对齐业界（AgentSkills spec + openclaw）的渐进式 Skill 架构。核心四件事：(1) SKILL.md 三级物理分层（L1 frontmatter / L2 骨架 body / L3 references/scripts/assets）；(2) 激活路径归一到 `skill.load` 工具（废除 `file.read(skill=…)` 捷径和 `SkillDisclosureTool` 空壳）；(3) `skills` 表作为多渠道安装状态的唯一事实源（BUILTIN / USER_IMPORTED / MARKETPLACE / AUTO_GENERATED 四种来源）；(4) 自生成链路重写（`SkillSynthesizer` 替代 `SkillGenerator`，校验管线合一）。

**Architecture：**
1. **规范层**：`docs/skill-spec.md` 是 SKILL.md 元规范；`skill-creator` 内置 skill 是自举样本；frontmatter 新字段 `metadata.zhiwei.{requires, suggested_tools, tags, category, priority}`
2. **解析层**：`MarkdownSkillParser` 认新 frontmatter；`SkillFrontmatter` record 替代旧 `SkillDefinition` 杂项字段
3. **校验层**：`SkillDescriptionValidator`（描述工程硬约束）+ `SkillBodyValidator`（骨架结构 + 字数硬限）+ `SkillValidator`（安全/format 合一）+ `SkillRequirementGate`（加载期 bins/env/os/tools 硬过滤）
4. **安装层**：`SkillInstaller` 统一四来源入库；`SkillDiscoveryRegistrar` 改写为 BUILTIN 安装器；`SkillImportService`（本地 .skill 包 + git URL）；`SkillMarketplaceInstaller`（已有市场 API 对接）
5. **运行时层**：`SkillLoadTool` 新 BuiltinTool，唯一激活入口；`SkillActivator` 升级含 `{skill_dir}` 占位符替换；`FileReadToolExecutor` 删 `executeSkillRead()` 并加路径白名单
6. **分发层**：`ContextAssembler.buildSkillCatalog()` 查 `skills` 表 `WHERE enabled=1`，按 category 分组 + priority 排序生成结构化 XML；`skill-catalog.st` 模板重写
7. **生成层**：`SkillSynthesizer` 替代 `SkillGenerator`；`SkillLifecycleEvent.Generated` 事件触发前端 SSE toast；前端 `SkillManageView` 按 source_type 显徽章

**Tech Stack：** Spring Boot 3 + Java 22（record / sealed / pattern matching）、SQLite + Flyway V17、Caffeine、JdbcTemplate、Spring AI ToolCallback、JUnit 5 + jqwik + Mockito + ApplicationContextRunner + AssertJ、Vue 3 + Reka UI 2.x + Tailwind 命名尺度、Pinia、lucide-vue-next

---

## File Structure

### 后端新建

| 路径 | 责任 |
|---|---|
| `src/main/resources/db/migration/V28__skill_system_refactor.sql` | drop + recreate `skills` 表 + 索引 |
| `docs/skill-spec.md` | SKILL.md 规范文档（frontmatter 字段表 + body 结构 + 三级分层） |
| `src/main/resources/skills/skill-creator/SKILL.md` | 自举元 skill（按新规范示范） |
| `src/main/resources/skills/skill-creator/references/frontmatter-reference.md` | frontmatter 字段详表 |
| `src/main/resources/skills/skill-creator/references/body-structure.md` | body 小节模板 |
| `src/main/java/com/lifepilot/skill/spec/SkillFrontmatter.java` | record：name / description / version / metadata |
| `src/main/java/com/lifepilot/skill/spec/SkillRequires.java` | record：bins / env / os / tools |
| `src/main/java/com/lifepilot/skill/spec/SkillZhiweiMeta.java` | record：requires / suggestedTools / tags / category / priority |
| `src/main/java/com/lifepilot/skill/spec/SkillPriority.java` | enum：HIGH / NORMAL / LOW |
| `src/main/java/com/lifepilot/skill/validation/SkillDescriptionValidator.java` | 硬约束：≤1024 / 中或英文触发开头 / 禁工作流词 |
| `src/main/java/com/lifepilot/skill/validation/SkillBodyValidator.java` | 必需小节 + body ≤ 5000 字符 |
| `src/main/java/com/lifepilot/skill/validation/SkillValidator.java` | 替代三件套：Format/Security/Sandbox 合一 |
| `src/main/java/com/lifepilot/skill/validation/SkillRequirementGate.java` | 加载期过滤 bins/env/os/tools |
| `src/main/java/com/lifepilot/skill/install/SkillInstallation.java` | record：name / sourceType / sourceUri / filePath / version / enabled / marketplaceId / checksum / installedAt / updatedAt / lastActivatedAt |
| `src/main/java/com/lifepilot/skill/install/SkillInstallationRepository.java` | JdbcTemplate CRUD + findByEnabled / findBySourceType / setEnabled / updateChecksum |
| `src/main/java/com/lifepilot/skill/install/SkillSourceType.java` | enum：BUILTIN / USER_IMPORTED / MARKETPLACE / AUTO_GENERATED |
| `src/main/java/com/lifepilot/skill/install/SkillInstaller.java` | 统一四来源安装：writeFile + validate + upsertDb |
| `src/main/java/com/lifepilot/skill/install/SkillImportService.java` | 本地 .skill 压缩包 + git URL 导入 |
| `src/main/java/com/lifepilot/skill/install/SkillMarketplaceInstaller.java` | 对接市场 API 下载安装 |
| `src/main/java/com/lifepilot/skill/tool/SkillLoadTool.java` | BuiltinTool 元数据：skill.load |
| `src/main/java/com/lifepilot/skill/tool/SkillLoadToolExecutor.java` | 执行器：ids 校验 + SkillActivator 激活 + 返回 instructions + activated_tool_ids |
| `src/main/java/com/lifepilot/skill/generation/SkillSynthesizer.java` | 替代 SkillGenerator：LLM → validate → 落库 |
| `src/main/java/com/lifepilot/skill/generation/SkillSynthesisContext.java` | record：gapDescription / targetScenario / availableTools |
| `src/main/java/com/lifepilot/skill/event/SkillGeneratedEvent.java` | ApplicationEvent：skillName / sourceType / at |
| `src/main/java/com/lifepilot/tool/validation/SkillPathWhitelist.java` | file.read 路径白名单校验器 |

### 后端修改

| 路径 | 改动 |
|---|---|
| `src/main/java/com/lifepilot/skill/MarkdownSkillParser.java` | 重写：识别新 frontmatter，拒绝旧 `id` 字段（必须 `name`），解析 `metadata.zhiwei` 嵌套块 |
| `src/main/java/com/lifepilot/skill/MarkdownSkillLoader.java` | 改造：加载后查表 `enabled`，不再 new `SkillDefinition` 直塞 Registry，改由 SkillInstaller 驱动 |
| `src/main/java/com/lifepilot/skill/SkillDefinition.java` | 替换字段：`id` → `name`；加 `SkillZhiweiMeta zhiweiMeta` |
| `src/main/java/com/lifepilot/skill/SkillRegistry.java` | API 更名 `getById` → `getByName`；加 `getEnabledCatalog()` 查表驱动 |
| `src/main/java/com/lifepilot/skill/SkillActivator.java` | `activate` 返回值加 `skillDir` / `referencesDir`；替换 `{skill_dir}` `{skill_references_dir}` 占位符；废除 `canActivate` 对 userConfirmed 检查（改为查 `enabled`） |
| `src/main/java/com/lifepilot/skill/SkillController.java` | REST 补：PUT /api/skills/{name}/enable、POST /api/skills/import、POST /api/skills/install-from-marketplace；字段重命名 |
| `src/main/java/com/lifepilot/skill/SkillDiscoveryRegistrar.java` | 改为 BUILTIN 安装器：classpath → SkillInstaller.install(source=BUILTIN) |
| `src/main/java/com/lifepilot/skill/SkillAutoConfiguration.java` | 增加新 Bean 装配；去掉 SkillDisclosureTool 依赖 |
| `src/main/java/com/lifepilot/agent/ReactAgentLoop.java` | 删 `detectSkillToolActivation()`（逻辑迁 SkillLoadToolExecutor）；删 `mcp:` 前缀硬解析 |
| `src/main/java/com/lifepilot/agent/context/ContextAssembler.java` | `buildSkillCatalog()` 改查 `SkillInstallationRepository.findByEnabled(true)` + RequirementGate 过滤；XML 结构化；去 `stripYamlFrontmatter` 重复逻辑 |
| `src/main/java/com/lifepilot/tool/builtin/FileReadToolExecutor.java` | 删 `executeSkillRead()`；注入 `SkillPathWhitelist` 校验路径；删 `skill` 参数 |
| `src/main/resources/application.yml` | 删 `meta.skill-discovery.skill-paths` 21 条死配置；删 `core-tool-ids` 过期注释 |
| `src/main/resources/prompts/agent/skill-catalog.st` | 重写为结构化 XML 输出 |
| `src/main/resources/prompts/agent/react-system.st` | `<tool_protocol>` 段更新：Tier 1 → skill.load（场景化） → tools.search → 兜底 |
| `src/main/resources/prompts/generation/skill-generation-enhanced.st` | 废弃 |
| `src/main/resources/prompts/generation/skill-synthesis.st` | 新建（替代上条） |
| `src/main/resources/prompts/generation/skill-fix.st` | 重写对齐新校验器信号 |

### 后端删除

| 路径 | 原因 |
|---|---|
| `src/main/java/com/lifepilot/skill/SkillDisclosureTool.java` | 空壳类，load_skill 已迁至 skill.load |
| `src/test/java/com/lifepilot/skill/SkillDisclosureToolTest.java` | 对应测试 |
| `src/test/java/com/lifepilot/skill/SkillDisclosure_Agent_集成测试.java` | 对应集成测试 |
| `src/main/java/com/lifepilot/skill/generation/SkillGenerator.java` | 被 SkillSynthesizer 替代 |
| `src/main/java/com/lifepilot/skill/validation/FormatValidator.java` | 合入 SkillValidator |
| `src/main/java/com/lifepilot/skill/validation/SecurityValidator.java` | 合入 SkillValidator |
| `src/main/java/com/lifepilot/skill/validation/SandboxValidator.java` | 合入 SkillValidator |
| `src/main/java/com/lifepilot/skill/validation/SkillValidationPipeline.java` | 合入 SkillValidator |

### 前端新建

| 路径 | 责任 |
|---|---|
| `zhiwei-web/src/components/skill/SkillSourceBadge.vue` | 根据 sourceType 渲染 徽章（内置/导入/市场/AI 生成） |
| `zhiwei-web/src/components/skill/SkillInstallDialog.vue` | 上传 .skill 文件 + Git URL + 市场搜索三 Tab |
| `zhiwei-web/src/composables/useSkillLifecycleEvents.ts` | 订阅 `/api/skills/events` SSE，派发 toast |

### 前端修改

| 路径 | 改动 |
|---|---|
| `zhiwei-web/src/views/SkillManageView.vue` | 加启用开关 / 来源 filter / 徽章 / "导入"按钮；字段重命名 id→name |
| `zhiwei-web/src/components/skill/SkillForm.vue` | 按新 frontmatter 规范重构；表单分组：基本 / 依赖(requires) / 元数据 |
| `zhiwei-web/src/composables/useSkillStore.ts` | 新 API：enableSkill / disableSkill / importSkill |
| `zhiwei-web/src/views/SkillDetailView.vue` | 增加 references 文件列表 |

### 预置 Skill 迁移（26 个）

| 路径 | 改动 |
|---|---|
| `src/main/resources/skills/<name>/SKILL.md` × 26 | 按新 frontmatter 重写（去 `id`，加 `metadata.zhiwei.*`）；body 骨架化；拆 references/ |
| `src/main/resources/skills/<name>/references/*.md` | 按需新建，存 API 详表/错误手册/示例 |

### 测试

| 路径 | 操作 |
|---|---|
| `src/test/java/com/lifepilot/skill/spec/MarkdownSkillParser_新规范测试.java` | 新建 |
| `src/test/java/com/lifepilot/skill/validation/SkillDescriptionValidator_硬约束测试.java` | 新建 |
| `src/test/java/com/lifepilot/skill/validation/SkillBodyValidator_小节测试.java` | 新建 |
| `src/test/java/com/lifepilot/skill/validation/SkillValidator_合一测试.java` | 新建 |
| `src/test/java/com/lifepilot/skill/validation/SkillRequirementGate_门控测试.java` | 新建 |
| `src/test/java/com/lifepilot/skill/install/SkillInstallationRepository_持久化测试.java` | 新建 |
| `src/test/java/com/lifepilot/skill/install/SkillInstaller_四来源测试.java` | 新建 |
| `src/test/java/com/lifepilot/skill/install/SkillImportService_导入测试.java` | 新建 |
| `src/test/java/com/lifepilot/skill/tool/SkillLoadToolExecutor_激活测试.java` | 新建 |
| `src/test/java/com/lifepilot/skill/SkillActivator_占位符替换测试.java` | 新建 |
| `src/test/java/com/lifepilot/tool/builtin/FileReadToolExecutor_路径白名单测试.java` | 新建 |
| `src/test/java/com/lifepilot/agent/context/ContextAssembler_Catalog重构测试.java` | 修改 |
| `src/test/java/com/lifepilot/skill/generation/SkillSynthesizer_管线测试.java` | 新建 |
| `src/test/java/com/lifepilot/skill/event/SkillGeneratedEvent_发布测试.java` | 新建 |
| `src/test/java/com/lifepilot/integration/SkillSystemRefactor_端到端集成测试.java` | 新建 |
| `src/test/java/com/lifepilot/skill/MarkdownSkillParserTest.java` | 重写 |
| `src/test/java/com/lifepilot/skill/SkillRegistryTest.java` | 修改（API 更名） |
| `src/test/java/com/lifepilot/skill/SkillIdDot_BugCondition_探索测试.java` | 删除（ID 正则统一后失效） |

### 文档

| 路径 | 改动 |
|---|---|
| `docs/skill-spec.md` | 新建 |
| `docs/architecture/skill-system.md` | 改写：反映新架构 |
| `docs/features/skill-system.md` | 改写：用户视角的 skill 使用指南 |
| `docs/features/skill-development.md` | 改写：引用 skill-creator skill 作为开发入口 |

---

## Phase 分布总览

| Phase | 范围 | 天 |
|---|---|---|
| Phase 0 | 规范 + 解析 + 校验 + V17 迁移 + 安装仓库 | 2.5 |
| Phase A | 激活路径重构（skill.load 工具 + Activator + Catalog + 清理） | 2 |
| Phase B | requires 门控 + 多渠道安装 + 前端改造 | 2 |
| Phase C | 自生成重写 + 事件 + 前端通知 | 2 |
| Phase D | 26 个预置 skill 迁移 + references 拆分 | 2 |
| Phase E | E2E 冒烟 + 文档同步 | 1 |

合计 11.5 天。

---

## Phase 0 · 规范 + 解析 + 校验 + 仓库

### Task 0.1：写 `docs/skill-spec.md`（SKILL.md 规范文档）

**Files:**
- Create: `docs/skill-spec.md`

- [ ] **Step 1：撰写规范文档**

按以下大纲落到 `docs/skill-spec.md`，目标读者是"要写新 skill 的开发者"和"写 skill-creator prompt 的维护者"。

```markdown
# ZhiWei Skill 规范 v2

## 1. Skill 目录结构（三级物理分层）

每个 skill 是 `<skills 根目录>/<skill-name>/` 下的一个文件夹：

    skills/<name>/
    ├── SKILL.md              # 必需。L1 frontmatter + L2 骨架 body
    ├── references/           # 可选。L3 按需加载的详细参考
    │   └── *.md              #   LLM 用 file.read(path=...) 主动加载
    ├── scripts/              # 可选。可执行脚本（不入 context）
    │   └── *.{sh,py,js}
    └── assets/               # 可选。模板/schema/静态资源（不入 context）
        └── *

## 2. SKILL.md 结构

### 2.1 Frontmatter（YAML，必需）

| 字段 | 必需 | 类型 | 约束 |
|---|---|---|---|
| `name` | 是 | string | 正则 `^[a-z0-9][a-z0-9-]{0,62}$`，等于目录名 |
| `description` | 是 | string | ≤1024 字符，"当…时使用" 或 "Use when…" 开头，不得含工作流词（步骤 1 / 首先 / 然后） |
| `version` | 是 | string | 语义化版本 semver，如 `1.0.0` |
| `metadata.zhiwei` | 否 | object | 下表字段 |

### 2.2 `metadata.zhiwei` 子字段

| 字段 | 类型 | 用途 |
|---|---|---|
| `suggested_tools` | `List<string>` | 激活后合并进 activatedToolIds（软引导） |
| `tags` | `List<string>` | 辅助检索 |
| `category` | string | external-integration / content-creation / automation / infrastructure / utility |
| `priority` | enum | `high` / `normal` / `low`，影响 catalog 排序 |
| `requires.bins` | `List<string>` | 运行依赖的二进制（如 `git`, `gh`, `sqlite3`） |
| `requires.env` | `List<string>` | 必需环境变量（名称，不含值） |
| `requires.os` | `List<string>` | OS 白名单 `windows` / `darwin` / `linux` |
| `requires.tools` | `List<string>` | 必需的已注册工具 id |

### 2.3 Body（Markdown）

推荐 4 段式，`SkillBodyValidator` 强制前 3 段：

    ## 适用场景（必需）
    - 2-5 条，每条描述一个典型场景

    ## 不适用场景（必需）
    - 反例，压抑误触发

    ## 工作流（必需）
    - 高层步骤骨架，不写细节命令
    - 需要详细参数/示例/错误处理时引用 references/

    ## 详细参考（可选）
    - 工具参数：参见 {skill_dir}/references/api-details.md
    - 常见错误：参见 {skill_dir}/references/error-handbook.md

- Body 硬限 ≤ 5000 字符。超长强制拆 references。
- `{skill_dir}` / `{skill_references_dir}` 占位符由 `SkillActivator` 替换为实际路径。

## 3. References 文件

- 只是普通 Markdown
- 文件名 snake-case 或 kebab-case，描述性
- SKILL.md body 里用自然语言引用（`参见 {skill_dir}/references/api.md`）
- LLM 用 `file.read(path="...")` 加载

## 4. 安装来源

- `BUILTIN`：ZhiWei 内置，随 classpath 分发
- `USER_IMPORTED`：用户上传 .skill 包或 Git URL 导入
- `MARKETPLACE`：从 ZhiWei 市场下载
- `AUTO_GENERATED`：SkillSynthesizer 自动产出，安装后即启用但在前端显眼标识

## 5. 校验规则

| 校验器 | 规则 |
|---|---|
| `SkillFrontmatterValidator` | 必需字段存在 + name 正则 + version semver |
| `SkillDescriptionValidator` | 描述 ≤1024 + 开头触发词 + 禁工作流词 |
| `SkillBodyValidator` | body ≤5000 + 必需 3 小节 |
| `SkillValidator`（合一） | format + 无命令注入风险 + 无 secrets |
| `SkillRequirementGate` | 加载期检查 requires.bins/env/os/tools，未满足不进 catalog |
```

- [ ] **Step 2：Commit**

```bash
git add docs/skill-spec.md
git commit -m "docs(skill): 新增 SKILL.md 规范文档 v2"
```

---

### Task 0.2：V28 迁移 — drop + recreate `skills` 表

**Files:**
- Create: `src/main/resources/db/migration/V28__skill_system_refactor.sql`

- [ ] **Step 1：写迁移脚本**

```sql
-- V28：Skill 系统重构 —— skills 表重建为"安装元数据事实源"
-- 老 skills 表无代码写入，数据可抛。新表只存安装状态，内容实时从 SKILL.md 读。

DROP TABLE IF EXISTS skills;

CREATE TABLE skills (
    name                TEXT PRIMARY KEY,
    source_type         TEXT NOT NULL,
    source_uri          TEXT,
    file_path           TEXT NOT NULL,
    version             TEXT NOT NULL,
    enabled             INTEGER NOT NULL DEFAULT 1,
    marketplace_id      TEXT,
    checksum            TEXT,
    installed_at        TEXT NOT NULL,
    updated_at          TEXT NOT NULL,
    last_activated_at   TEXT,
    CHECK (source_type IN ('BUILTIN', 'USER_IMPORTED', 'MARKETPLACE', 'AUTO_GENERATED')),
    CHECK (enabled IN (0, 1))
);

CREATE INDEX idx_skills_source_enabled ON skills(source_type, enabled);
CREATE INDEX idx_skills_enabled ON skills(enabled);
```

- [ ] **Step 2：验证编译 + Flyway 应用**

```bash
mvn -q compile
mvn -q test -Dtest=FlywayMigrationVerificationTest
```

- [ ] **Step 3：Commit**

```bash
git add src/main/resources/db/migration/V28__skill_system_refactor.sql
git commit -m "feat(db): V28 迁移 — skills 表重建为安装元数据事实源"
```

---

### Task 0.3：`SkillInstallation` record + `SkillSourceType` enum

**Files:**
- Create: `src/main/java/com/lifepilot/skill/install/SkillInstallation.java`
- Create: `src/main/java/com/lifepilot/skill/install/SkillSourceType.java`

- [ ] **Step 1：写 `SkillSourceType`**

```java
package com.lifepilot.skill.install;

/**
 * Skill 安装来源类型。
 *
 * @author zsg
 * @since 2026-04-24
 */
public enum SkillSourceType {
    BUILTIN,
    USER_IMPORTED,
    MARKETPLACE,
    AUTO_GENERATED
}
```

- [ ] **Step 2：写 `SkillInstallation`**

```java
package com.lifepilot.skill.install;

import java.time.Instant;

/**
 * Skill 安装元数据 —— 对应 skills 表一行。
 *
 * @author zsg
 * @since 2026-04-24
 */
public record SkillInstallation(
        String name,
        SkillSourceType sourceType,
        String sourceUri,
        String filePath,
        String version,
        boolean enabled,
        String marketplaceId,
        String checksum,
        Instant installedAt,
        Instant updatedAt,
        Instant lastActivatedAt
) {
    public SkillInstallation {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name 不能为空");
        if (sourceType == null) throw new IllegalArgumentException("sourceType 不能为空");
        if (filePath == null || filePath.isBlank()) throw new IllegalArgumentException("filePath 不能为空");
        if (version == null || version.isBlank()) throw new IllegalArgumentException("version 不能为空");
    }
}
```

- [ ] **Step 3：Commit**

```bash
git add src/main/java/com/lifepilot/skill/install/SkillInstallation.java \
        src/main/java/com/lifepilot/skill/install/SkillSourceType.java
git commit -m "feat(skill): 新增 SkillInstallation record + SkillSourceType enum"
```

---

### Task 0.4：`SkillInstallationRepository` + 测试

**Files:**
- Create: `src/main/java/com/lifepilot/skill/install/SkillInstallationRepository.java`
- Create: `src/test/java/com/lifepilot/skill/install/SkillInstallationRepository_持久化测试.java`

- [ ] **Step 1：写失败测试**

```java
package com.lifepilot.skill.install;

import com.lifepilot.test.DatabaseTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SkillInstallationRepository 持久化行为测试。
 *
 * @author zsg
 * @since 2026-04-24
 */
@SpringBootTest
class SkillInstallationRepository_持久化测试 extends DatabaseTestSupport {

    @Autowired SkillInstallationRepository repository;

    @Test
    void 应能保存并按名字查找() {
        var install = fixture("github-workflow", SkillSourceType.BUILTIN, true);
        repository.upsert(install);

        var found = repository.findByName("github-workflow");
        assertThat(found).isPresent();
        assertThat(found.get().sourceType()).isEqualTo(SkillSourceType.BUILTIN);
    }

    @Test
    void 按启用状态查询应仅返回enabled的() {
        repository.upsert(fixture("a", SkillSourceType.BUILTIN, true));
        repository.upsert(fixture("b", SkillSourceType.BUILTIN, false));
        repository.upsert(fixture("c", SkillSourceType.BUILTIN, true));

        assertThat(repository.findAllByEnabled(true))
                .extracting(SkillInstallation::name).containsExactlyInAnyOrder("a", "c");
    }

    @Test
    void setEnabled应原子更新enabled与updated_at() {
        repository.upsert(fixture("x", SkillSourceType.BUILTIN, true));
        var before = repository.findByName("x").get().updatedAt();

        repository.setEnabled("x", false);

        var after = repository.findByName("x").get();
        assertThat(after.enabled()).isFalse();
        assertThat(after.updatedAt()).isAfterOrEqualTo(before);
    }

    @Test
    void 按来源类型过滤() {
        repository.upsert(fixture("a", SkillSourceType.BUILTIN, true));
        repository.upsert(fixture("b", SkillSourceType.AUTO_GENERATED, true));

        assertThat(repository.findAllBySourceType(SkillSourceType.AUTO_GENERATED))
                .extracting(SkillInstallation::name).containsExactly("b");
    }

    private SkillInstallation fixture(String name, SkillSourceType type, boolean enabled) {
        return new SkillInstallation(name, type, null, "/p/" + name, "1.0.0",
                enabled, null, "sha", Instant.now(), Instant.now(), null);
    }
}
```

- [ ] **Step 2：Run test to verify fails**

```bash
mvn -q test -Dtest=SkillInstallationRepository_持久化测试
```

Expected: FAIL（类 `SkillInstallationRepository` 不存在）

- [ ] **Step 3：写 Repository 实现**

```java
package com.lifepilot.skill.install;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * SkillInstallation 持久化仓库（skills 表）。
 *
 * @author zsg
 * @since 2026-04-24
 */
@Repository
public class SkillInstallationRepository {

    private final JdbcTemplate jdbc;

    public SkillInstallationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void upsert(SkillInstallation s) {
        jdbc.update("""
                INSERT INTO skills (name, source_type, source_uri, file_path, version,
                                    enabled, marketplace_id, checksum,
                                    installed_at, updated_at, last_activated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(name) DO UPDATE SET
                  source_type = excluded.source_type,
                  source_uri = excluded.source_uri,
                  file_path = excluded.file_path,
                  version = excluded.version,
                  enabled = excluded.enabled,
                  marketplace_id = excluded.marketplace_id,
                  checksum = excluded.checksum,
                  updated_at = excluded.updated_at,
                  last_activated_at = excluded.last_activated_at
                """,
                s.name(), s.sourceType().name(), s.sourceUri(), s.filePath(), s.version(),
                s.enabled() ? 1 : 0, s.marketplaceId(), s.checksum(),
                Timestamp.from(s.installedAt()), Timestamp.from(s.updatedAt()),
                s.lastActivatedAt() == null ? null : Timestamp.from(s.lastActivatedAt()));
    }

    public Optional<SkillInstallation> findByName(String name) {
        var list = jdbc.query("SELECT * FROM skills WHERE name = ?",
                (rs, i) -> mapRow(rs), name);
        return list.stream().findFirst();
    }

    public List<SkillInstallation> findAllByEnabled(boolean enabled) {
        return jdbc.query("SELECT * FROM skills WHERE enabled = ? ORDER BY name",
                (rs, i) -> mapRow(rs), enabled ? 1 : 0);
    }

    public List<SkillInstallation> findAllBySourceType(SkillSourceType type) {
        return jdbc.query("SELECT * FROM skills WHERE source_type = ? ORDER BY name",
                (rs, i) -> mapRow(rs), type.name());
    }

    public void setEnabled(String name, boolean enabled) {
        jdbc.update("UPDATE skills SET enabled = ?, updated_at = ? WHERE name = ?",
                enabled ? 1 : 0, Timestamp.from(Instant.now()), name);
    }

    public void updateLastActivatedAt(String name, Instant at) {
        jdbc.update("UPDATE skills SET last_activated_at = ? WHERE name = ?",
                Timestamp.from(at), name);
    }

    public void delete(String name) {
        jdbc.update("DELETE FROM skills WHERE name = ?", name);
    }

    private SkillInstallation mapRow(ResultSet rs) throws SQLException {
        return new SkillInstallation(
                rs.getString("name"),
                SkillSourceType.valueOf(rs.getString("source_type")),
                rs.getString("source_uri"),
                rs.getString("file_path"),
                rs.getString("version"),
                rs.getInt("enabled") == 1,
                rs.getString("marketplace_id"),
                rs.getString("checksum"),
                rs.getTimestamp("installed_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(),
                rs.getTimestamp("last_activated_at") == null
                        ? null : rs.getTimestamp("last_activated_at").toInstant()
        );
    }
}
```

- [ ] **Step 4：Run test to verify passes**

```bash
mvn -q test -Dtest=SkillInstallationRepository_持久化测试
```

Expected: PASS（4 个用例）

- [ ] **Step 5：Commit**

```bash
git add src/main/java/com/lifepilot/skill/install/SkillInstallationRepository.java \
        src/test/java/com/lifepilot/skill/install/SkillInstallationRepository_持久化测试.java
git commit -m "feat(skill): SkillInstallationRepository 新增 + 持久化测试"
```

---

### Task 0.5：Frontmatter records（`SkillFrontmatter` / `SkillZhiweiMeta` / `SkillRequires` / `SkillPriority`）

**Files:**
- Create: `src/main/java/com/lifepilot/skill/spec/SkillPriority.java`
- Create: `src/main/java/com/lifepilot/skill/spec/SkillRequires.java`
- Create: `src/main/java/com/lifepilot/skill/spec/SkillZhiweiMeta.java`
- Create: `src/main/java/com/lifepilot/skill/spec/SkillFrontmatter.java`

- [ ] **Step 1：写 `SkillPriority`**

```java
package com.lifepilot.skill.spec;

/**
 * Skill 优先级。影响 catalog 排序。
 *
 * @author zsg
 * @since 2026-04-24
 */
public enum SkillPriority { HIGH, NORMAL, LOW }
```

- [ ] **Step 2：写 `SkillRequires`**

```java
package com.lifepilot.skill.spec;

import java.util.List;

/**
 * Skill 运行期依赖声明。加载期由 SkillRequirementGate 硬过滤。
 *
 * @author zsg
 * @since 2026-04-24
 */
public record SkillRequires(
        List<String> bins,
        List<String> env,
        List<String> os,
        List<String> tools
) {
    public static SkillRequires empty() {
        return new SkillRequires(List.of(), List.of(), List.of(), List.of());
    }

    public SkillRequires {
        bins = bins == null ? List.of() : List.copyOf(bins);
        env = env == null ? List.of() : List.copyOf(env);
        os = os == null ? List.of() : List.copyOf(os);
        tools = tools == null ? List.of() : List.copyOf(tools);
    }
}
```

- [ ] **Step 3：写 `SkillZhiweiMeta`**

```java
package com.lifepilot.skill.spec;

import java.util.List;

/**
 * SKILL.md frontmatter 下 `metadata.zhiwei` 块的结构化视图。
 *
 * @author zsg
 * @since 2026-04-24
 */
public record SkillZhiweiMeta(
        List<String> suggestedTools,
        List<String> tags,
        String category,
        SkillPriority priority,
        SkillRequires requires
) {
    public static SkillZhiweiMeta empty() {
        return new SkillZhiweiMeta(List.of(), List.of(), null, SkillPriority.NORMAL, SkillRequires.empty());
    }

    public SkillZhiweiMeta {
        suggestedTools = suggestedTools == null ? List.of() : List.copyOf(suggestedTools);
        tags = tags == null ? List.of() : List.copyOf(tags);
        priority = priority == null ? SkillPriority.NORMAL : priority;
        requires = requires == null ? SkillRequires.empty() : requires;
    }
}
```

- [ ] **Step 4：写 `SkillFrontmatter`**

```java
package com.lifepilot.skill.spec;

/**
 * SKILL.md frontmatter 的结构化表示。
 *
 * @author zsg
 * @since 2026-04-24
 */
public record SkillFrontmatter(
        String name,
        String description,
        String version,
        SkillZhiweiMeta zhiweiMeta
) {
    public SkillFrontmatter {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name 不能为空");
        if (description == null || description.isBlank()) throw new IllegalArgumentException("description 不能为空");
        if (version == null || version.isBlank()) throw new IllegalArgumentException("version 不能为空");
        zhiweiMeta = zhiweiMeta == null ? SkillZhiweiMeta.empty() : zhiweiMeta;
    }
}
```

- [ ] **Step 5：编译验证**

```bash
mvn -q compile
```

- [ ] **Step 6：Commit**

```bash
git add src/main/java/com/lifepilot/skill/spec/
git commit -m "feat(skill): frontmatter 新规范 records（SkillFrontmatter/ZhiweiMeta/Requires/Priority）"
```

---

### Task 0.6：重写 `MarkdownSkillParser` + 测试

**Files:**
- Modify: `src/main/java/com/lifepilot/skill/MarkdownSkillParser.java`
- Create: `src/test/java/com/lifepilot/skill/spec/MarkdownSkillParser_新规范测试.java`

- [ ] **Step 1：写失败测试**

```java
package com.lifepilot.skill.spec;

import com.lifepilot.skill.MarkdownSkillParser;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MarkdownSkillParser 对新 frontmatter 规范的解析行为测试。
 *
 * @author zsg
 * @since 2026-04-24
 */
class MarkdownSkillParser_新规范测试 {

    private final MarkdownSkillParser parser = new MarkdownSkillParser();

    @Test
    void 解析必需字段() {
        var md = """
                ---
                name: github-workflow
                description: 当需要创建/合并/审查 PR 时使用。关键词: pr / issue / ci
                version: 1.0.0
                ---
                ## 适用场景
                - 创建 PR
                """;

        var parsed = parser.parse(md);

        assertThat(parsed.frontmatter().name()).isEqualTo("github-workflow");
        assertThat(parsed.frontmatter().version()).isEqualTo("1.0.0");
        assertThat(parsed.body()).contains("## 适用场景");
    }

    @Test
    void 解析metadata_zhiwei嵌套块() {
        var md = """
                ---
                name: x
                description: 当需要测试时使用
                version: 1.0.0
                metadata:
                  zhiwei:
                    suggested_tools: [a, b]
                    tags: [foo]
                    category: automation
                    priority: high
                    requires:
                      bins: [git]
                      env: [GITHUB_TOKEN]
                      os: [linux]
                      tools: [gh.pr.create]
                ---
                body
                """;

        var parsed = parser.parse(md);
        var meta = parsed.frontmatter().zhiweiMeta();

        assertThat(meta.suggestedTools()).containsExactly("a", "b");
        assertThat(meta.tags()).containsExactly("foo");
        assertThat(meta.category()).isEqualTo("automation");
        assertThat(meta.priority()).isEqualTo(SkillPriority.HIGH);
        assertThat(meta.requires().bins()).containsExactly("git");
        assertThat(meta.requires().env()).containsExactly("GITHUB_TOKEN");
        assertThat(meta.requires().os()).containsExactly("linux");
        assertThat(meta.requires().tools()).containsExactly("gh.pr.create");
    }

    @Test
    void 老字段id应被拒绝() {
        var md = """
                ---
                id: foo
                name: foo
                description: 当用时
                version: 1.0.0
                ---
                body
                """;

        assertThatThrownBy(() -> parser.parse(md))
                .hasMessageContaining("字段 'id' 已废弃")
                .hasMessageContaining("请用 'name'");
    }

    @Test
    void name不符合正则应拒绝() {
        var md = """
                ---
                name: Foo_Bar
                description: 当用时
                version: 1.0.0
                ---
                body
                """;

        assertThatThrownBy(() -> parser.parse(md))
                .hasMessageContaining("name 必须匹配正则");
    }

    @Test
    void 缺少必需字段应拒绝() {
        var md = "---\nname: x\n---\nbody";

        assertThatThrownBy(() -> parser.parse(md))
                .hasMessageContaining("缺少必需字段");
    }
}
```

- [ ] **Step 2：Run test — verify fails**

```bash
mvn -q test -Dtest=MarkdownSkillParser_新规范测试
```

Expected: FAIL（老 parser 不认新字段）

- [ ] **Step 3：重写 `MarkdownSkillParser`**

关键实现点：
- 用 SnakeYAML 解析 frontmatter
- 必需字段校验：`name / description / version`
- 遇到 `id` 字段直接抛异常（不兼容老格式）
- name 正则校验
- 读 `metadata.zhiwei` 嵌套块为 `SkillZhiweiMeta`
- 返回 `ParsedSkill(SkillFrontmatter frontmatter, String body)` record

```java
package com.lifepilot.skill;

import com.lifepilot.skill.spec.SkillFrontmatter;
import com.lifepilot.skill.spec.SkillPriority;
import com.lifepilot.skill.spec.SkillRequires;
import com.lifepilot.skill.spec.SkillZhiweiMeta;
import org.yaml.snakeyaml.Yaml;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 解析 SKILL.md（frontmatter + body）。遵守 docs/skill-spec.md v2 规范。
 *
 * @author zsg
 * @since 2026-04-24
 */
public class MarkdownSkillParser {

    private static final Pattern FRONTMATTER = Pattern.compile(
            "^---\\s*\\n(.+?)\\n---\\s*\\n(.*)$", Pattern.DOTALL);
    private static final Pattern NAME_REGEX = Pattern.compile("^[a-z0-9][a-z0-9-]{0,62}$");
    private static final List<String> REQUIRED = List.of("name", "description", "version");

    public record ParsedSkill(SkillFrontmatter frontmatter, String body) {}

    @SuppressWarnings("unchecked")
    public ParsedSkill parse(String content) {
        var matcher = FRONTMATTER.matcher(content.strip());
        if (!matcher.matches()) {
            throw new IllegalArgumentException("SKILL.md 必须以 YAML frontmatter 开头");
        }
        Map<String, Object> raw = new Yaml().load(matcher.group(1));
        if (raw == null) raw = Map.of();

        if (raw.containsKey("id")) {
            throw new IllegalArgumentException(
                    "字段 'id' 已废弃，请用 'name'（见 docs/skill-spec.md §2.1）");
        }
        for (String f : REQUIRED) {
            if (!raw.containsKey(f) || raw.get(f) == null) {
                throw new IllegalArgumentException("缺少必需字段: " + f);
            }
        }
        String name = raw.get("name").toString();
        if (!NAME_REGEX.matcher(name).matches()) {
            throw new IllegalArgumentException(
                    "name 必须匹配正则 ^[a-z0-9][a-z0-9-]{0,62}$，实际: " + name);
        }

        SkillZhiweiMeta meta = parseZhiweiMeta(raw);

        var fm = new SkillFrontmatter(
                name,
                raw.get("description").toString(),
                raw.get("version").toString(),
                meta);

        return new ParsedSkill(fm, matcher.group(2));
    }

    @SuppressWarnings("unchecked")
    private SkillZhiweiMeta parseZhiweiMeta(Map<String, Object> raw) {
        Map<String, Object> metadata = (Map<String, Object>) raw.getOrDefault("metadata", Map.of());
        Map<String, Object> zhiwei = (Map<String, Object>) metadata.getOrDefault("zhiwei", Map.of());
        if (zhiwei == null) return SkillZhiweiMeta.empty();

        var suggestedTools = asStringList(zhiwei.get("suggested_tools"));
        var tags = asStringList(zhiwei.get("tags"));
        var category = zhiwei.get("category") == null ? null : zhiwei.get("category").toString();
        var priority = zhiwei.get("priority") == null
                ? SkillPriority.NORMAL
                : SkillPriority.valueOf(zhiwei.get("priority").toString().toUpperCase());

        Map<String, Object> req = (Map<String, Object>) zhiwei.getOrDefault("requires", Map.of());
        var requires = new SkillRequires(
                asStringList(req.get("bins")),
                asStringList(req.get("env")),
                asStringList(req.get("os")),
                asStringList(req.get("tools")));

        return new SkillZhiweiMeta(suggestedTools, tags, category, priority, requires);
    }

    @SuppressWarnings("unchecked")
    private List<String> asStringList(Object o) {
        if (o == null) return List.of();
        if (o instanceof List<?> l) {
            return l.stream().map(Object::toString).toList();
        }
        return List.of(o.toString());
    }
}
```

- [ ] **Step 4：Run test — verify passes**

```bash
mvn -q test -Dtest=MarkdownSkillParser_新规范测试
```

Expected: PASS（5 个用例）

- [ ] **Step 5：Commit**

```bash
git add src/main/java/com/lifepilot/skill/MarkdownSkillParser.java \
        src/test/java/com/lifepilot/skill/spec/
git commit -m "feat(skill): MarkdownSkillParser 重写为 v2 规范，拒绝老 id 字段"
```

---

### Task 0.7：`SkillDescriptionValidator` + 测试

**Files:**
- Create: `src/main/java/com/lifepilot/skill/validation/SkillDescriptionValidator.java`
- Create: `src/test/java/com/lifepilot/skill/validation/SkillDescriptionValidator_硬约束测试.java`

- [ ] **Step 1：写失败测试**

```java
package com.lifepilot.skill.validation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SkillDescriptionValidator 硬约束测试。
 *
 * @author zsg
 * @since 2026-04-24
 */
class SkillDescriptionValidator_硬约束测试 {

    private final SkillDescriptionValidator v = new SkillDescriptionValidator();

    @Test
    void 中文当时使用开头应通过() {
        assertThatCode(() -> v.validate("当需要创建 PR 时使用。关键词: pr / review"))
                .doesNotThrowAnyException();
    }

    @Test
    void Use_when开头应通过() {
        assertThatCode(() -> v.validate("Use when creating a PR. Keywords: pr, review"))
                .doesNotThrowAnyException();
    }

    @Test
    void 超过1024字符应拒绝() {
        String s = "当" + "a".repeat(1024);
        assertThatThrownBy(() -> v.validate(s))
                .hasMessageContaining("≤1024");
    }

    @Test
    void 工作流词汇应拒绝() {
        assertThatThrownBy(() -> v.validate("当使用时执行。步骤 1: 打开浏览器"))
                .hasMessageContaining("工作流词");
        assertThatThrownBy(() -> v.validate("用于 X。首先打开浏览器"))
                .hasMessageContaining("工作流词");
    }

    @Test
    void 非触发词开头应拒绝() {
        assertThatThrownBy(() -> v.validate("这是一个 github 工具"))
                .hasMessageContaining("开头");
    }
}
```

- [ ] **Step 2：Run — verify fails**

```bash
mvn -q test -Dtest=SkillDescriptionValidator_硬约束测试
```

- [ ] **Step 3：写实现**

```java
package com.lifepilot.skill.validation;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 对 SKILL.md 的 description 做硬约束校验。
 *
 * 规则（见 docs/skill-spec.md §2.1 和 §5）：
 * 1. ≤1024 字符
 * 2. 以 "当..." / "用于..." / "Use when..." 开头
 * 3. 不得包含工作流词（步骤 1 / 首先 / 然后 / Step 1 / First）
 *
 * @author zsg
 * @since 2026-04-24
 */
@Component
public class SkillDescriptionValidator {

    private static final int MAX = 1024;
    private static final Pattern START = Pattern.compile(
            "^\\s*(当|用于|Use when|Use this when)", Pattern.CASE_INSENSITIVE);
    private static final List<Pattern> WORKFLOW_WORDS = List.of(
            Pattern.compile("步骤\\s*[0-9]"),
            Pattern.compile("首先"),
            Pattern.compile("然后"),
            Pattern.compile("接下来"),
            Pattern.compile("\\bStep\\s*[0-9]", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bFirst\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bThen\\b", Pattern.CASE_INSENSITIVE)
    );

    public void validate(String description) {
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("description 不能为空");
        }
        if (description.length() > MAX) {
            throw new IllegalArgumentException(
                    "description 长度超过 ≤" + MAX + " 字符限制（当前 " + description.length() + "）");
        }
        if (!START.matcher(description).find()) {
            throw new IllegalArgumentException(
                    "description 必须以 '当...' / '用于...' / 'Use when...' 开头（见 docs/skill-spec.md §2.1）");
        }
        for (Pattern p : WORKFLOW_WORDS) {
            if (p.matcher(description).find()) {
                throw new IllegalArgumentException(
                        "description 不得含工作流词（步骤/首先/然后/Step N），工作流应写在 body。命中: " + p.pattern());
            }
        }
    }
}
```

- [ ] **Step 4：Run — verify passes**

```bash
mvn -q test -Dtest=SkillDescriptionValidator_硬约束测试
```

- [ ] **Step 5：Commit**

```bash
git add src/main/java/com/lifepilot/skill/validation/SkillDescriptionValidator.java \
        src/test/java/com/lifepilot/skill/validation/SkillDescriptionValidator_硬约束测试.java
git commit -m "feat(skill): SkillDescriptionValidator 硬约束（字数/开头/禁工作流）"
```

---

### Task 0.8：`SkillBodyValidator` + 测试

**Files:**
- Create: `src/main/java/com/lifepilot/skill/validation/SkillBodyValidator.java`
- Create: `src/test/java/com/lifepilot/skill/validation/SkillBodyValidator_小节测试.java`

- [ ] **Step 1：写失败测试**

```java
package com.lifepilot.skill.validation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SkillBodyValidator 小节结构测试。
 *
 * @author zsg
 * @since 2026-04-24
 */
class SkillBodyValidator_小节测试 {

    private final SkillBodyValidator v = new SkillBodyValidator();

    @Test
    void 齐三必需小节应通过() {
        var body = """
                # X 指南
                ## 适用场景
                - a
                ## 不适用场景
                - b
                ## 工作流
                - c
                """;
        assertThatCode(() -> v.validate(body)).doesNotThrowAnyException();
    }

    @Test
    void 缺少小节应拒绝() {
        var body = "# X\n## 适用场景\n- a\n## 工作流\n- b";
        assertThatThrownBy(() -> v.validate(body))
                .hasMessageContaining("不适用场景");
    }

    @Test
    void 超5000字符应拒绝() {
        var body = "# X\n## 适用场景\n" + "a".repeat(5001);
        assertThatThrownBy(() -> v.validate(body))
                .hasMessageContaining("≤5000");
    }
}
```

- [ ] **Step 2：Run — fails**

- [ ] **Step 3：写实现**

```java
package com.lifepilot.skill.validation;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * SKILL.md body 小节结构与长度校验。
 *
 * @author zsg
 * @since 2026-04-24
 */
@Component
public class SkillBodyValidator {

    private static final int MAX = 5000;
    private static final List<String> REQUIRED_HEADINGS = List.of(
            "## 适用场景", "## 不适用场景", "## 工作流");

    public void validate(String body) {
        if (body == null || body.isBlank()) {
            throw new IllegalArgumentException("body 不能为空");
        }
        if (body.length() > MAX) {
            throw new IllegalArgumentException(
                    "body 长度超过 ≤" + MAX + " 字符限制（当前 " + body.length()
                    + "），请拆分详细内容到 references/ 子目录");
        }
        for (String h : REQUIRED_HEADINGS) {
            if (!body.contains(h)) {
                throw new IllegalArgumentException(
                        "body 缺少必需小节: " + h + "（见 docs/skill-spec.md §2.3）");
            }
        }
    }
}
```

- [ ] **Step 4：Run — passes**

- [ ] **Step 5：Commit**

```bash
git add src/main/java/com/lifepilot/skill/validation/SkillBodyValidator.java \
        src/test/java/com/lifepilot/skill/validation/SkillBodyValidator_小节测试.java
git commit -m "feat(skill): SkillBodyValidator 小节结构 + 字数硬限"
```

---

**Phase 0 退出条件：** 新规范文档 + V17 迁移 + 安装仓库 + 新 parser + 2 个单字段校验器齐备；`mvn test` 全绿；commits 有清晰分段。

---

## Phase A · 激活路径重构

### Task A.1：`SkillLoadTool` + Executor + 测试

**Files:**
- Create: `src/main/java/com/lifepilot/skill/tool/SkillLoadTool.java`
- Create: `src/main/java/com/lifepilot/skill/tool/SkillLoadToolExecutor.java`
- Create: `src/test/java/com/lifepilot/skill/tool/SkillLoadToolExecutor_激活测试.java`

- [ ] **Step 1：写 `SkillLoadTool`（BuiltinTool 元数据）**

```java
package com.lifepilot.skill.tool;

import com.lifepilot.tool.model.BuiltinTool;
import com.lifepilot.tool.model.ToolCategory;
import com.lifepilot.tool.model.ToolRiskLevel;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * skill.load —— 激活 1-3 个 Skill，返回指南正文与 suggestedTools。
 *
 * @author zsg
 * @since 2026-04-24
 */
@Component
public class SkillLoadTool {

    public BuiltinTool definition() {
        return BuiltinTool.builder()
                .id("skill.load")
                .description("Activate one to three ZhiWei skills by name. Returns the SKILL.md body (with placeholders resolved) and merges each skill's suggested_tools into the current tool visibility set.")
                .category(ToolCategory.META)
                .riskLevel(ToolRiskLevel.LOW)
                .tags(List.of("skill", "activate", "load", "guide"))
                .schema(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "names", Map.of(
                                        "type", "array",
                                        "items", Map.of("type", "string"),
                                        "description", "Skill names to activate (1-3 allowed)"
                                )),
                        "required", List.of("names")))
                .build();
    }
}
```

- [ ] **Step 2：写失败测试**

```java
package com.lifepilot.skill.tool;

import com.lifepilot.skill.SkillActivator;
import com.lifepilot.skill.SkillActivation;
import com.lifepilot.skill.install.SkillInstallation;
import com.lifepilot.skill.install.SkillInstallationRepository;
import com.lifepilot.skill.install.SkillSourceType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * skill.load 工具的激活行为测试。
 *
 * @author zsg
 * @since 2026-04-24
 */
@ExtendWith(MockitoExtension.class)
class SkillLoadToolExecutor_激活测试 {

    @Mock SkillActivator activator;
    @Mock SkillInstallationRepository repo;
    @InjectMocks SkillLoadToolExecutor executor;

    @Test
    void 应拒绝超过3个skill() {
        assertThatThrownBy(() ->
                executor.execute(Map.of("names", List.of("a", "b", "c", "d"))))
                .hasMessageContaining("最多 3 个");
    }

    @Test
    void 应拒绝已禁用的skill() {
        when(repo.findByName("x")).thenReturn(Optional.of(disabled("x")));

        assertThatThrownBy(() -> executor.execute(Map.of("names", List.of("x"))))
                .hasMessageContaining("已被禁用");
    }

    @Test
    void 激活成功应返回body与activatedToolIds() {
        when(repo.findByName("x")).thenReturn(Optional.of(enabled("x")));
        when(activator.activate("x")).thenReturn(
                new SkillActivation("x", "## 指南\n...", List.of("tool-a")));

        var result = executor.execute(Map.of("names", List.of("x")));

        assertThat(result).containsKeys("content", "activated_tool_ids");
        assertThat((String) result.get("content")).contains("## 指南");
        assertThat((List<?>) result.get("activated_tool_ids")).contains("tool-a");
    }

    @Test
    void 多skill内容应按顺序拼接() {
        when(repo.findByName("a")).thenReturn(Optional.of(enabled("a")));
        when(repo.findByName("b")).thenReturn(Optional.of(enabled("b")));
        when(activator.activate("a")).thenReturn(
                new SkillActivation("a", "AAA", List.of("t1")));
        when(activator.activate("b")).thenReturn(
                new SkillActivation("b", "BBB", List.of("t2")));

        var result = executor.execute(Map.of("names", List.of("a", "b")));

        assertThat((String) result.get("content")).contains("AAA").contains("BBB");
        assertThat((List<?>) result.get("activated_tool_ids"))
                .containsExactlyInAnyOrder("t1", "t2");
    }

    private SkillInstallation enabled(String n) {
        return new SkillInstallation(n, SkillSourceType.BUILTIN, null, "/p", "1.0.0",
                true, null, "sha", Instant.now(), Instant.now(), null);
    }

    private SkillInstallation disabled(String n) {
        return new SkillInstallation(n, SkillSourceType.BUILTIN, null, "/p", "1.0.0",
                false, null, "sha", Instant.now(), Instant.now(), null);
    }
}
```

- [ ] **Step 3：Run — verify fails**

- [ ] **Step 4：写 `SkillLoadToolExecutor`**

```java
package com.lifepilot.skill.tool;

import com.lifepilot.skill.SkillActivator;
import com.lifepilot.skill.install.SkillInstallationRepository;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 执行 skill.load 工具：校验 names、查表 enabled、调 Activator、聚合结果。
 *
 * @author zsg
 * @since 2026-04-24
 */
@Component
public class SkillLoadToolExecutor {

    private static final int MAX_SKILLS = 3;

    private final SkillActivator activator;
    private final SkillInstallationRepository repository;

    public SkillLoadToolExecutor(SkillActivator activator, SkillInstallationRepository repository) {
        this.activator = activator;
        this.repository = repository;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> execute(Map<String, Object> params) {
        List<String> names = (List<String>) params.get("names");
        if (names == null || names.isEmpty()) {
            throw new IllegalArgumentException("参数 names 不能为空");
        }
        if (names.size() > MAX_SKILLS) {
            throw new IllegalArgumentException("一次最多 3 个 skill，当前 " + names.size());
        }

        var contentParts = new ArrayList<String>();
        var toolIds = new HashSet<String>();

        for (String name : names) {
            var install = repository.findByName(name)
                    .orElseThrow(() -> new IllegalArgumentException("未知 skill: " + name));
            if (!install.enabled()) {
                throw new IllegalArgumentException("skill '" + name + "' 已被禁用，无法激活");
            }
            var activation = activator.activate(name);
            contentParts.add(
                    "<skill name=\"" + name + "\">\n" + activation.instructions() + "\n</skill>");
            toolIds.addAll(activation.suggestedTools());
        }

        return Map.of(
                "content", String.join("\n\n", contentParts),
                "activated_tool_ids", List.copyOf(toolIds));
    }
}
```

- [ ] **Step 5：Run — passes**

- [ ] **Step 6：Commit**

```bash
git add src/main/java/com/lifepilot/skill/tool/ \
        src/test/java/com/lifepilot/skill/tool/
git commit -m "feat(skill): 新增 skill.load 工具 + Executor（统一激活入口）"
```

---

### Task A.2：`SkillActivator` 升级（占位符替换 + 查表校验）

**Files:**
- Modify: `src/main/java/com/lifepilot/skill/SkillActivator.java`
- Modify: `src/main/java/com/lifepilot/skill/SkillActivation.java`（或新建）
- Modify: `src/test/java/com/lifepilot/skill/SkillActivator_占位符替换测试.java`（新增）

- [ ] **Step 1：更新 `SkillActivation` record**

```java
package com.lifepilot.skill;

import java.util.List;

/**
 * Skill 激活结果：已替换占位符的 body + 建议工具。
 *
 * @author zsg
 * @since 2026-04-24
 */
public record SkillActivation(String name, String instructions, List<String> suggestedTools) {}
```

- [ ] **Step 2：写失败测试**

```java
package com.lifepilot.skill;

import com.lifepilot.skill.install.SkillInstallation;
import com.lifepilot.skill.install.SkillInstallationRepository;
import com.lifepilot.skill.install.SkillSourceType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SkillActivator_占位符替换测试 {

    @Mock SkillRegistry registry;
    @Mock SkillInstallationRepository repo;
    @InjectMocks SkillActivator activator;

    @Test
    void skill_dir占位符应被替换为实际路径() {
        when(registry.getByName("x")).thenReturn(Optional.of(
                definition("x", "参见 {skill_dir}/references/api.md", List.of("a"))));
        when(repo.findByName("x")).thenReturn(Optional.of(install("x", "/skills/x")));

        var a = activator.activate("x");

        assertThat(a.instructions()).contains("参见 /skills/x/references/api.md");
    }

    @Test
    void 禁用的skill激活应抛异常() {
        when(repo.findByName("x")).thenReturn(Optional.of(install("x", "/p", false)));
        assertThatThrownBy(() -> activator.activate("x"))
                .hasMessageContaining("已禁用");
    }

    // 省略 definition/install 辅助方法
}
```

- [ ] **Step 3：更新 `SkillActivator`**

主要变化：
- 构造器注入 `SkillInstallationRepository`
- `activate(String name)` 先查 `repo.findByName` 校验 enabled
- 从 `SkillRegistry.getByName` 取 instructions（内存缓存，热加载维护）
- 替换 `{skill_dir}` → `install.filePath()`
- 替换 `{skill_references_dir}` → `install.filePath() + "/references"`
- 替换 `{skill_scripts_dir}` → `install.filePath() + "/scripts"`（已有，保留）
- 废除 `canActivate(AutoGenerated.userConfirmed)` 检查（改查 `enabled`）
- 返回 `SkillActivation`
- 更新 `last_activated_at` 异步

- [ ] **Step 4：Run test passes**

- [ ] **Step 5：Commit**

```bash
git commit -am "refactor(skill): SkillActivator 升级 — 查表校验 + 三占位符替换"
```

---

### Task A.3：注册 `skill.load` 为 BuiltinTool + Tier 1 pinned

**Files:**
- Modify: `src/main/java/com/lifepilot/skill/SkillAutoConfiguration.java`
- Modify: `src/main/resources/application.yml`

- [ ] **Step 1：注册 BuiltinTool**

在 `SkillAutoConfiguration` 或新 `SkillToolRegistrar` 里：

```java
@Bean
SkillToolRegistrar skillToolRegistrar(
        BuiltinToolRegistry toolRegistry,
        SkillLoadTool skillLoadTool,
        SkillLoadToolExecutor skillLoadExecutor,
        ToolValidator toolValidator) {
    return new SkillToolRegistrar(toolRegistry, skillLoadTool, skillLoadExecutor, toolValidator);
}
```

`SkillToolRegistrar` 在 `@EventListener(ApplicationReadyEvent.class) @Order(LOWEST_PRECEDENCE)` 里注册 `skill.load` 到 `DynamicToolRegistry`，执行 `ToolValidator.validate(skillLoadTool.definition())` 通过后 `BuiltinToolRegistrar.register(...)`。

- [ ] **Step 2：加入 Tier 1 pinned 列表**

`application.yml`:

```yaml
lifepilot:
  tool:
    tier1:
      pinned:
        - file.read
        - file.write
        - memory.remember
        - memory.forget
        - datastore.query
        - shell.exec
        - tools.search
        - tools.describe
        - tools.list
        - skill.load   # 新增
```

- [ ] **Step 3：启动验证**

```bash
mvn spring-boot:run
# 访问 http://localhost:8080/actuator/tools-metadata 确认 skill.load 存在且在 Tier 1
```

- [ ] **Step 4：Commit**

```bash
git commit -am "feat(skill): skill.load 注册为 BuiltinTool + 加入 Tier 1 pinned"
```

---

### Task A.4：删除 `SkillDisclosureTool` 空壳

**Files:**
- Delete: `src/main/java/com/lifepilot/skill/SkillDisclosureTool.java`
- Delete: `src/test/java/com/lifepilot/skill/SkillDisclosureToolTest.java`
- Delete: `src/test/java/com/lifepilot/skill/SkillDisclosure_Agent_集成测试.java`
- Modify: `src/main/java/com/lifepilot/skill/SkillAutoConfiguration.java`（删除该 Bean 装配）

- [ ] **Step 1：grep 残留引用**

```bash
grep -rn "SkillDisclosureTool" src/
```

确认只有被删的三处。

- [ ] **Step 2：删除文件 + 清理 AutoConfiguration**

```bash
rm src/main/java/com/lifepilot/skill/SkillDisclosureTool.java
rm src/test/java/com/lifepilot/skill/SkillDisclosureToolTest.java
rm src/test/java/com/lifepilot/skill/SkillDisclosure_Agent_集成测试.java
```

编辑 `SkillAutoConfiguration` 删除 `SkillDisclosureTool` Bean 和相关 registerTools 调用。

- [ ] **Step 3：编译通过**

```bash
mvn -q compile
mvn -q test
```

- [ ] **Step 4：Commit**

```bash
git add -A
git commit -m "refactor(skill): 删除 SkillDisclosureTool 空壳及测试"
```

---

### Task A.5：删除 `ReactAgentLoop.detectSkillToolActivation()`

**Files:**
- Modify: `src/main/java/com/lifepilot/agent/ReactAgentLoop.java`

- [ ] **Step 1：定位并删除**

`ReactAgentLoop.java:1316-1362` 的 `detectSkillToolActivation()` 整方法删除。
`ReactAgentLoop.java:380-392` 调用 `detectSkillToolActivation(...)` 并合并 `activatedToolIds / skillContent` 的逻辑删除。

原因：新架构下 `SkillLoadToolExecutor` 直接返回 `activated_tool_ids`，`ToolExecutionCoordinator` 在执行结果里找到这个键就 merge 到 `state.withActivatedToolIds(...)`。迁移点在 `ToolExecutionCoordinator`。

- [ ] **Step 2：改 `ToolExecutionCoordinator` 通用合并**

```java
// 在 ToolExecutionCoordinator.executeOne(...) 执行完成后：
var result = executor.execute(params);
if (result instanceof Map<?, ?> map && map.containsKey("activated_tool_ids")) {
    @SuppressWarnings("unchecked")
    var toolIds = (List<String>) map.get("activated_tool_ids");
    state.withActivatedToolIds(Stream.concat(
            state.activatedToolIds().stream(), toolIds.stream()).distinct().toList());
}
if (result instanceof Map<?, ?> map && map.containsKey("content")) {
    String content = (String) map.get("content");
    // skill.load 的 content 是 <skill>...</skill> 包装，直接追加到 loadedSkillContent
    if (content.startsWith("<skill")) {
        state.appendSkillContent(content);
    }
}
```

（或把这个合并逻辑放到 `SkillLoadToolExecutor` 里返回更结构化的结果对象，`ToolExecutionCoordinator` 用 `instanceof pattern` 分发。选择在 TDD 后定下来。）

- [ ] **Step 3：编译 + 测试**

```bash
mvn -q compile && mvn -q test -Dtest="ReactAgentLoop*"
```

- [ ] **Step 4：Commit**

```bash
git commit -am "refactor(agent): 删除 detectSkillToolActivation 黑魔法，改由 SkillLoadToolExecutor 驱动"
```

---

### Task A.6：`FileReadToolExecutor` 删 `executeSkillRead` + 路径白名单

**Files:**
- Modify: `src/main/java/com/lifepilot/tool/builtin/FileReadToolExecutor.java`
- Create: `src/main/java/com/lifepilot/tool/validation/SkillPathWhitelist.java`
- Create: `src/test/java/com/lifepilot/tool/builtin/FileReadToolExecutor_路径白名单测试.java`

- [ ] **Step 1：写 `SkillPathWhitelist`**

```java
package com.lifepilot.tool.validation;

import com.lifepilot.skill.SkillConfigProperties;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 校验 file.read 路径是否在白名单内（skills 目录 + 工作区）。
 * 防 path traversal 读系统敏感文件。
 *
 * @author zsg
 * @since 2026-04-24
 */
@Component
public class SkillPathWhitelist {

    private final Path skillsRoot;
    private final Path workspaceRoot;

    public SkillPathWhitelist(SkillConfigProperties config) {
        this.skillsRoot = Paths.get(config.getDirectory()).toAbsolutePath().normalize();
        this.workspaceRoot = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
    }

    public void validate(String pathStr) {
        Path p = Paths.get(pathStr).toAbsolutePath().normalize();
        if (p.startsWith(skillsRoot) || p.startsWith(workspaceRoot)) return;
        throw new SecurityException("file.read 路径超出白名单: " + p
                + "（允许范围: " + skillsRoot + ", " + workspaceRoot + ")");
    }
}
```

- [ ] **Step 2：写测试**

```java
package com.lifepilot.tool.builtin;

import com.lifepilot.skill.SkillConfigProperties;
import com.lifepilot.tool.validation.SkillPathWhitelist;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileReadToolExecutor_路径白名单测试 {

    @Test
    void 合法路径应通过() {
        var config = new SkillConfigProperties();
        config.setDirectory(System.getProperty("java.io.tmpdir"));
        var w = new SkillPathWhitelist(config);

        assertThatCode(() -> w.validate(System.getProperty("java.io.tmpdir") + "/a.md"))
                .doesNotThrowAnyException();
    }

    @Test
    void 非法系统路径应拒绝() {
        var config = new SkillConfigProperties();
        config.setDirectory(System.getProperty("java.io.tmpdir"));
        var w = new SkillPathWhitelist(config);

        assertThatThrownBy(() -> w.validate("/etc/passwd"))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("超出白名单");
    }

    @Test
    void path_traversal应拒绝() {
        var config = new SkillConfigProperties();
        config.setDirectory(System.getProperty("java.io.tmpdir"));
        var w = new SkillPathWhitelist(config);

        assertThatThrownBy(() -> w.validate(System.getProperty("java.io.tmpdir") + "/../../../etc/passwd"))
                .isInstanceOf(SecurityException.class);
    }
}
```

- [ ] **Step 3：改 `FileReadToolExecutor`**

- 在 `execute(params)` 开头注入 `SkillPathWhitelist.validate(path)`
- 删除整个 `executeSkillRead()` 方法（`FileReadToolExecutor.java:400-485`）
- 删除 `skill` 参数的分支；file.read schema 只保留 `path`

- [ ] **Step 4：Run tests**

```bash
mvn -q test -Dtest="FileRead*"
```

- [ ] **Step 5：Commit**

```bash
git commit -am "refactor(tool): file.read 删除 skill 捷径 + 增加路径白名单"
```

---

### Task A.7：`ContextAssembler.buildSkillCatalog()` 重构

**Files:**
- Modify: `src/main/java/com/lifepilot/agent/context/ContextAssembler.java`
- Create: `src/test/java/com/lifepilot/agent/context/ContextAssembler_Catalog重构测试.java`

- [ ] **Step 1：写失败测试（行为契约）**

```java
class ContextAssembler_Catalog重构测试 {

    @Test
    void catalog应只包含enabled的skill() {
        // fixture: a=enabled, b=disabled
        // expect: 输出只含 a
    }

    @Test
    void catalog应按category分组_同组内按priority排序() {
        // fixture: 3 个 category, priority 混合
        // expect: 输出按 category 分组, 组内 high > normal > low
    }

    @Test
    void requires未满足的skill应被过滤() {
        // fixture: x.requires.bins=[nonexistent-binary]
        // expect: 输出不含 x
    }
}
```

- [ ] **Step 2：改 `buildSkillCatalog()`**

```java
public String buildSkillCatalog() {
    var enabled = installationRepository.findAllByEnabled(true);
    var available = enabled.stream()
            .filter(install -> {
                var def = registry.getByName(install.name()).orElse(null);
                if (def == null) return false;
                return requirementGate.satisfies(def.zhiweiMeta().requires());
            })
            .toList();

    // 按 category 分组
    Map<String, List<SkillDefinition>> grouped = available.stream()
            .map(install -> registry.getByName(install.name()).orElseThrow())
            .collect(groupingBy(d -> defaultIfNull(d.zhiweiMeta().category(), "other")));

    // 同组按 priority 排序
    var sb = new StringBuilder();
    for (var entry : grouped.entrySet()) {
        sb.append("<category name=\"").append(entry.getKey()).append("\">\n");
        entry.getValue().stream()
                .sorted(comparing(d -> priorityOrder(d.zhiweiMeta().priority())))
                .forEach(d -> sb.append(renderSkill(d)));
        sb.append("</category>\n");
    }
    return sb.toString();
}

private String renderSkill(SkillDefinition d) {
    return "  <skill name=\"" + d.name() + "\">\n"
            + "    <description>" + escapeXml(d.description()) + "</description>\n"
            + "  </skill>\n";
}
```

- [ ] **Step 3：删除 `ContextAssembler.stripYamlFrontmatter()` 的重复实现**

改为复用 `MarkdownSkillParser.parse(...).body()`。

- [ ] **Step 4：Run tests**

- [ ] **Step 5：Commit**

```bash
git commit -am "refactor(context): Skill catalog 查表驱动 + category 分组 + priority 排序"
```

---

### Task A.8：重写 `skill-catalog.st` 模板

**Files:**
- Modify: `src/main/resources/prompts/agent/skill-catalog.st`
- Modify: `src/main/resources/prompts/agent/react-system.st`（工具协议段落）

- [ ] **Step 1：重写 `skill-catalog.st`**

```
<skill_catalog>
{skillCatalogXml}
</skill_catalog>

## 使用规则
- 匹配到合适 Skill → 调用 skill.load(names=["skill-name"]) 加载完整指南
- 多个 Skill 协同 → skill.load(names=["a","b"])，一次最多 3 个
- Skill 指南里引用 {skill_dir}/references/xxx.md 的详细文档 → 用 file.read(path="...") 按需加载
- 无匹配 Skill → 继续用 Tier 1 工具或 tools.search 发现工具
```

- [ ] **Step 2：更新 `react-system.st::<tool_protocol>`**

去掉老的 `file.read(skill=…)` 引用，改为：
```
<tool_protocol>
1. Tier 1 常驻工具直接调用
2. 复杂场景化流程 → skill.load(names=[...]) 加载 Skill 指南
3. 需要未知工具 → tools.search(query="...") 发现
4. 核心工具即可完成 → 直接调用
</tool_protocol>
```

- [ ] **Step 3：启动验证 prompt 拼接**

```bash
mvn spring-boot:run
# tail logs/agent-prompt.log 确认 <skill_catalog> 段落结构正确
```

- [ ] **Step 4：Commit**

```bash
git commit -am "refactor(prompt): skill-catalog.st 对齐新 skill.load 协议"
```

---

### Task A.9：删除 `application.yml` 死配置

**Files:**
- Modify: `src/main/resources/application.yml`

- [ ] **Step 1：删除行 935-956（`meta.skill-discovery.skill-paths`）**

该块无代码读取（`SkillDiscoveryRegistrar` 用 classpath wildcard）。直接整段删。

- [ ] **Step 2：删除行 62-64 附近关于 `core-tool-ids` 的过期注释**

`core-tool-ids` 早已废弃，注释仍在引导新人以为还有效。整行删或简化。

- [ ] **Step 3：启动验证配置不丢**

```bash
mvn spring-boot:run
```

无报错即通过。

- [ ] **Step 4：Commit**

```bash
git commit -am "chore(config): 删除 meta.skill-discovery.skill-paths 死配置和 core-tool-ids 过期注释"
```

---

**Phase A 退出条件：** `skill.load` 工具可用、`SkillActivator` 唯一激活路径、`SkillDisclosureTool` 空壳和 `file.read(skill=…)` 捷径都已清除、`ContextAssembler.buildSkillCatalog()` 查 DB 出 XML、catalog 模板对齐新协议、死配置清理。`mvn test` 全绿。

---

## Phase B · requires 门控 + 多渠道安装 + 前端

### Task B.1：`SkillRequirementGate` + 测试

**Files:**
- Create: `src/main/java/com/lifepilot/skill/validation/SkillRequirementGate.java`
- Create: `src/test/java/com/lifepilot/skill/validation/SkillRequirementGate_门控测试.java`

- [ ] **Step 1：写失败测试**

```java
package com.lifepilot.skill.validation;

import com.lifepilot.skill.spec.SkillRequires;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SkillRequirementGate_门控测试 {

    @Mock DynamicToolRegistry toolRegistry;

    @Test
    void 无依赖应通过() {
        var gate = new SkillRequirementGate(toolRegistry, () -> "linux", name -> true, key -> "val");
        assertThat(gate.satisfies(SkillRequires.empty())).isTrue();
    }

    @Test
    void 缺少bin应拒绝() {
        var gate = new SkillRequirementGate(toolRegistry, () -> "linux", name -> false, key -> "val");
        var req = new SkillRequires(List.of("nonexistent"), List.of(), List.of(), List.of());
        assertThat(gate.satisfies(req)).isFalse();
    }

    @Test
    void 缺少env应拒绝() {
        var gate = new SkillRequirementGate(toolRegistry, () -> "linux", name -> true, key -> null);
        var req = new SkillRequires(List.of(), List.of("GITHUB_TOKEN"), List.of(), List.of());
        assertThat(gate.satisfies(req)).isFalse();
    }

    @Test
    void os不匹配应拒绝() {
        var gate = new SkillRequirementGate(toolRegistry, () -> "windows", name -> true, key -> "v");
        var req = new SkillRequires(List.of(), List.of(), List.of("linux"), List.of());
        assertThat(gate.satisfies(req)).isFalse();
    }

    @Test
    void 缺少tool应拒绝() {
        when(toolRegistry.hasTool("gh.pr.create")).thenReturn(false);
        var gate = new SkillRequirementGate(toolRegistry, () -> "linux", n -> true, k -> "v");
        var req = new SkillRequires(List.of(), List.of(), List.of(), List.of("gh.pr.create"));
        assertThat(gate.satisfies(req)).isFalse();
    }
}
```

- [ ] **Step 2：Run — fails**

- [ ] **Step 3：写 `SkillRequirementGate`**

```java
package com.lifepilot.skill.validation;

import com.lifepilot.skill.spec.SkillRequires;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import org.springframework.stereotype.Component;

import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Skill 运行期依赖门控器 —— 加载期判断 bins / env / os / tools 是否满足。
 *
 * @author zsg
 * @since 2026-04-24
 */
@Component
public class SkillRequirementGate {

    private final DynamicToolRegistry toolRegistry;
    private final Supplier<String> osSupplier;
    private final Function<String, Boolean> binResolver;
    private final Function<String, String> envResolver;

    public SkillRequirementGate(DynamicToolRegistry toolRegistry) {
        this(toolRegistry, SkillRequirementGate::currentOs,
                SkillRequirementGate::binaryExistsOnPath, System::getenv);
    }

    public SkillRequirementGate(DynamicToolRegistry toolRegistry,
                                 Supplier<String> osSupplier,
                                 Function<String, Boolean> binResolver,
                                 Function<String, String> envResolver) {
        this.toolRegistry = toolRegistry;
        this.osSupplier = osSupplier;
        this.binResolver = binResolver;
        this.envResolver = envResolver;
    }

    public boolean satisfies(SkillRequires requires) {
        for (String bin : requires.bins()) {
            if (!binResolver.apply(bin)) return false;
        }
        for (String env : requires.env()) {
            String val = envResolver.apply(env);
            if (val == null || val.isBlank()) return false;
        }
        if (!requires.os().isEmpty() && !requires.os().contains(osSupplier.get())) return false;
        for (String toolId : requires.tools()) {
            if (!toolRegistry.hasTool(toolId)) return false;
        }
        return true;
    }

    private static String currentOs() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) return "windows";
        if (os.contains("mac")) return "darwin";
        return "linux";
    }

    private static boolean binaryExistsOnPath(String bin) {
        try {
            var p = Runtime.getRuntime().exec(new String[]{bin, "--version"});
            return p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            return false;
        }
    }
}
```

- [ ] **Step 4：Run — passes**

- [ ] **Step 5：Commit**

```bash
git commit -am "feat(skill): SkillRequirementGate 加载期 bins/env/os/tools 硬过滤"
```

---

### Task B.2：`SkillInstaller` 统一四来源入库

**Files:**
- Create: `src/main/java/com/lifepilot/skill/install/SkillInstaller.java`
- Create: `src/test/java/com/lifepilot/skill/install/SkillInstaller_四来源测试.java`

- [ ] **Step 1：写失败测试**

```java
class SkillInstaller_四来源测试 {

    @Test
    void install_BUILTIN应写入文件与表() {
        // fixture: classpath resource
        // expect: ~/.zhiwei/skills/x/SKILL.md 存在 + skills 表有 BUILTIN 记录
    }

    @Test
    void install_AUTO_GENERATED应enabled_1() {
        // expect: enabled=1（老板决策第 5 点）
    }

    @Test
    void 相同name重复install应upsert() {
        // expect: 不抛异常, version 更新, updated_at 变
    }

    @Test
    void 校验失败应回滚不落库() {
        // fixture: description 违反规范
        // expect: 抛异常, 文件不写, 表不写
    }
}
```

- [ ] **Step 2：写实现**

```java
package com.lifepilot.skill.install;

import com.lifepilot.skill.MarkdownSkillParser;
import com.lifepilot.skill.validation.SkillBodyValidator;
import com.lifepilot.skill.validation.SkillDescriptionValidator;
import com.lifepilot.skill.validation.SkillValidator;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;

/**
 * 统一 Skill 安装入口。四种来源（BUILTIN/USER_IMPORTED/MARKETPLACE/AUTO_GENERATED）
 * 都经过：parse → validate → writeFile → upsertDb 四步。
 *
 * @author zsg
 * @since 2026-04-24
 */
@Service
public class SkillInstaller {

    private final MarkdownSkillParser parser;
    private final SkillDescriptionValidator descriptionValidator;
    private final SkillBodyValidator bodyValidator;
    private final SkillValidator skillValidator;
    private final SkillInstallationRepository repository;

    public SkillInstaller(MarkdownSkillParser parser,
                          SkillDescriptionValidator descriptionValidator,
                          SkillBodyValidator bodyValidator,
                          SkillValidator skillValidator,
                          SkillInstallationRepository repository) {
        this.parser = parser;
        this.descriptionValidator = descriptionValidator;
        this.bodyValidator = bodyValidator;
        this.skillValidator = skillValidator;
        this.repository = repository;
    }

    public SkillInstallation install(InstallRequest request) throws IOException {
        var parsed = parser.parse(request.skillMdContent());
        descriptionValidator.validate(parsed.frontmatter().description());
        bodyValidator.validate(parsed.body());
        skillValidator.validate(parsed);

        Path dir = request.targetDir().resolve(parsed.frontmatter().name());
        Files.createDirectories(dir);
        Path file = dir.resolve("SKILL.md");
        Files.writeString(file, request.skillMdContent());

        // references / scripts / assets 由上层准备好对应文件，这里只记录目录
        String checksum = sha256(request.skillMdContent());
        Instant now = Instant.now();

        var install = new SkillInstallation(
                parsed.frontmatter().name(),
                request.sourceType(),
                request.sourceUri(),
                dir.toString(),
                parsed.frontmatter().version(),
                true,                                   // 默认启用
                request.marketplaceId(),
                checksum,
                now, now, null);

        repository.upsert(install);
        return install;
    }

    public record InstallRequest(
            SkillSourceType sourceType,
            String sourceUri,
            String marketplaceId,
            String skillMdContent,
            Path targetDir
    ) {}

    private static String sha256(String s) {
        try {
            var bytes = MessageDigest.getInstance("SHA-256").digest(s.getBytes());
            var sb = new StringBuilder();
            for (byte b : bytes) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
```

- [ ] **Step 3：Run tests pass**

- [ ] **Step 4：Commit**

```bash
git commit -am "feat(skill): SkillInstaller 统一四来源安装入口（validate → writeFile → upsert）"
```

---

### Task B.3：`SkillDiscoveryRegistrar` 改造为 BUILTIN 安装器

**Files:**
- Modify: `src/main/java/com/lifepilot/skill/SkillDiscoveryRegistrar.java`

- [ ] **Step 1：改造**

原逻辑：扫 classpath → 复制到 `~/.zhiwei/skills/`（已存在跳过）。

新逻辑：扫 classpath → 调用 `SkillInstaller.install(InstallRequest(BUILTIN, "classpath:skills/" + name, null, content, skillsRoot))`。Installer 会处理 upsert 语义。

```java
@Override
public void afterPropertiesSet() throws Exception {
    var resolver = new PathMatchingResourcePatternResolver();
    var resources = resolver.getResources("classpath:skills/*/SKILL.md");
    for (Resource r : resources) {
        String content = IOUtils.toString(r.getInputStream(), StandardCharsets.UTF_8);
        String name = extractNameFromResource(r);   // 从路径取目录名
        installer.install(new SkillInstaller.InstallRequest(
                SkillSourceType.BUILTIN,
                "classpath:skills/" + name,
                null,
                content,
                Paths.get(config.getDirectory())));
        copyReferencesAndScripts(r, name);          // 复制 references/ / scripts/ / assets/
    }
}
```

- [ ] **Step 2：启动验证 `skills` 表填充**

```bash
mvn spring-boot:run
# 查库：sqlite3 ~/.zhiwei/zhiwei.db "SELECT name, source_type, enabled FROM skills;"
# 预期：26 行 BUILTIN / enabled=1
```

- [ ] **Step 3：Commit**

```bash
git commit -am "refactor(skill): SkillDiscoveryRegistrar 改造为 BUILTIN 安装器（调用 SkillInstaller）"
```

---

### Task B.4：`SkillImportService`（本地 .skill 包 + git URL）

**Files:**
- Create: `src/main/java/com/lifepilot/skill/install/SkillImportService.java`
- Create: `src/test/java/com/lifepilot/skill/install/SkillImportService_导入测试.java`

- [ ] **Step 1：写实现**

```java
package com.lifepilot.skill.install;

import com.lifepilot.skill.SkillConfigProperties;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.zip.ZipInputStream;

/**
 * 用户端 Skill 导入：.skill 压缩包 / Git URL。
 *
 * @author zsg
 * @since 2026-04-24
 */
@Service
public class SkillImportService {

    private final SkillInstaller installer;
    private final SkillConfigProperties config;

    public SkillImportService(SkillInstaller installer, SkillConfigProperties config) {
        this.installer = installer;
        this.config = config;
    }

    public SkillInstallation importFromPackage(MultipartFile file) throws IOException {
        // 安全检查: 拒绝 symlink / path traversal
        // 解压 → 读 SKILL.md → installer.install(USER_IMPORTED, sourceUri=file://...)
        // references/scripts/assets 一同解压到目标目录
        Path tempDir = Files.createTempDirectory("skill-import-");
        try (var zis = new ZipInputStream(file.getInputStream())) {
            unzipSafe(zis, tempDir);
        }
        Path skillMd = tempDir.resolve("SKILL.md");
        if (!Files.exists(skillMd)) {
            throw new IllegalArgumentException(".skill 包根目录必须含 SKILL.md");
        }
        String content = Files.readString(skillMd);
        var install = installer.install(new SkillInstaller.InstallRequest(
                SkillSourceType.USER_IMPORTED,
                "file://" + file.getOriginalFilename(),
                null, content, Paths.get(config.getDirectory())));
        copyAuxFiles(tempDir, Paths.get(install.filePath()));
        return install;
    }

    public SkillInstallation importFromGitUrl(String gitUrl) throws IOException {
        // 用 JGit clone 到 tempdir → 同上
        throw new UnsupportedOperationException("TODO Phase B.4 后期");
    }

    private void unzipSafe(ZipInputStream zis, Path target) { /* ... */ }
    private void copyAuxFiles(Path src, Path dst) { /* references/scripts/assets */ }
}
```

- [ ] **Step 2：测试（.skill 包路径，Git URL 路径先 stub）**

```java
@Test
void 导入skill包应写入目录与表() { /* ... */ }

@Test
void zip内path_traversal应拒绝() { /* ... */ }

@Test
void 缺少SKILL_md应拒绝() { /* ... */ }
```

- [ ] **Step 3：Run + Commit**

```bash
git commit -am "feat(skill): SkillImportService .skill 包导入（Git URL 留 phase 后期）"
```

---

### Task B.5：`SkillMarketplaceInstaller` 对接现有市场 API

**Files:**
- Create: `src/main/java/com/lifepilot/skill/install/SkillMarketplaceInstaller.java`
- Modify: `src/main/java/com/lifepilot/marketplace/...`（对接点根据实际市场模块调整）

- [ ] **Step 1：接入已有市场模块**

```java
package com.lifepilot.skill.install;

import com.lifepilot.marketplace.MarketplaceClient;
import com.lifepilot.skill.SkillConfigProperties;
import org.springframework.stereotype.Service;

import java.nio.file.Paths;

@Service
public class SkillMarketplaceInstaller {

    private final MarketplaceClient client;
    private final SkillInstaller installer;
    private final SkillConfigProperties config;

    public SkillMarketplaceInstaller(MarketplaceClient client,
                                      SkillInstaller installer,
                                      SkillConfigProperties config) {
        this.client = client;
        this.installer = installer;
        this.config = config;
    }

    public SkillInstallation installById(String marketplaceId) throws Exception {
        var pkg = client.downloadSkill(marketplaceId);
        return installer.install(new SkillInstaller.InstallRequest(
                SkillSourceType.MARKETPLACE,
                "market://" + marketplaceId,
                marketplaceId,
                pkg.skillMdContent(),
                Paths.get(config.getDirectory())));
    }
}
```

（若项目中没有 `MarketplaceClient`，创建 stub 接口，真实对接留给后续。）

- [ ] **Step 2：测试 + Commit**

```bash
git commit -am "feat(skill): SkillMarketplaceInstaller 对接市场客户端"
```

---

### Task B.6：`SkillController` REST 改造

**Files:**
- Modify: `src/main/java/com/lifepilot/skill/SkillController.java`

- [ ] **Step 1：新增 REST 端点**

```java
@PutMapping("/api/skills/{name}/enabled")
public ApiResponse<Void> setEnabled(@PathVariable String name,
                                     @RequestBody Map<String, Boolean> body) {
    installationRepository.setEnabled(name, body.get("enabled"));
    return ApiResponse.ok();
}

@PostMapping("/api/skills/import")
public ApiResponse<SkillInstallation> importPackage(@RequestParam MultipartFile file) throws IOException {
    return ApiResponse.ok(importService.importFromPackage(file));
}

@PostMapping("/api/skills/install-from-marketplace")
public ApiResponse<SkillInstallation> installFromMarketplace(
        @RequestBody Map<String, String> body) throws Exception {
    return ApiResponse.ok(marketplaceInstaller.installById(body.get("marketplaceId")));
}

@GetMapping("/api/skills")
public ApiResponse<PageResponse<SkillListItem>> list(
        @RequestParam(required = false) SkillSourceType sourceType,
        @RequestParam(required = false) Boolean enabled) {
    // 联合查 skills 表 + SkillRegistry 得 name/description/version + 安装元数据
    return ApiResponse.ok(...);
}
```

- [ ] **Step 2：字段统一重命名 id → name**

所有返回体改 `name`。前端 store 同步改。

- [ ] **Step 3：Commit**

```bash
git commit -am "feat(skill): SkillController 增加 enable/import/marketplace 端点 + 字段 name"
```

---

### Task B.7：前端 `SkillManageView` 改造

**Files:**
- Modify: `zhiwei-web/src/views/SkillManageView.vue`
- Create: `zhiwei-web/src/components/skill/SkillSourceBadge.vue`
- Create: `zhiwei-web/src/components/skill/SkillInstallDialog.vue`
- Modify: `zhiwei-web/src/composables/useSkillStore.ts`

- [ ] **Step 1：`SkillSourceBadge.vue`**

```vue
<script setup lang="ts">
import { computed } from 'vue'
import { Badge } from '@/components/ui/badge'
import { Package, Download, Upload, Sparkles } from 'lucide-vue-next'

const props = defineProps<{ sourceType: 'BUILTIN' | 'USER_IMPORTED' | 'MARKETPLACE' | 'AUTO_GENERATED' }>()

const meta = computed(() => {
  switch (props.sourceType) {
    case 'BUILTIN': return { icon: Package, label: '内置', variant: 'secondary' }
    case 'USER_IMPORTED': return { icon: Upload, label: '导入', variant: 'outline' }
    case 'MARKETPLACE': return { icon: Download, label: '市场', variant: 'default' }
    case 'AUTO_GENERATED': return { icon: Sparkles, label: 'AI 生成', variant: 'destructive' }
  }
})
</script>

<template>
  <Badge :variant="meta.variant" class="gap-xs">
    <component :is="meta.icon" class="h-sm w-sm" />
    {{ meta.label }}
  </Badge>
</template>
```

Tailwind 必须用命名尺度（`gap-xs` / `h-sm` / `w-sm`，见 `.claude/rules/frontend-conventions.md`）。

- [ ] **Step 2：`SkillInstallDialog.vue`**

三 Tab 对话框：
- Tab 1：上传 .skill 压缩包（文件拖拽）
- Tab 2：Git URL（文本输入 + 预览 SKILL.md）
- Tab 3：市场搜索（列表 + 安装按钮）

- [ ] **Step 3：`SkillManageView.vue` 列表增强**

- 每行显示 `SkillSourceBadge`
- 启用/禁用开关（调 `PUT /api/skills/{name}/enabled`）
- 工具栏"导入"按钮（打开 `SkillInstallDialog`）
- 来源 filter（BUILTIN/USER_IMPORTED/MARKETPLACE/AUTO_GENERATED）
- 所有 UI 文本中文

- [ ] **Step 4：Store 更新**

```typescript
// useSkillStore.ts
export const useSkillStore = defineStore('skill', () => {
  async function setEnabled(name: string, enabled: boolean) {
    await $fetch(`/api/skills/${name}/enabled`, { method: 'PUT', body: { enabled } })
  }
  async function importPackage(file: File) { /* ... */ }
  async function installFromMarketplace(id: string) { /* ... */ }
  // 字段全部改 name
})
```

- [ ] **Step 5：`npm run build` 编译通过**

```bash
cd zhiwei-web && npm run build
```

- [ ] **Step 6：`npm run test:run` 通过**

- [ ] **Step 7：Commit**

```bash
git commit -am "feat(web): SkillManageView 启用开关 + 来源徽章 + 导入对话框"
```

---

### Task B.8：前端 `SkillForm` 按新规范重构

**Files:**
- Modify: `zhiwei-web/src/components/skill/SkillForm.vue`

- [ ] **Step 1：表单分组重构**

- **基本信息**：name（替代 id） / description / version
- **依赖声明**：requires.bins / env / os / tools（分组输入）
- **元数据**：suggested_tools / tags / category / priority

- [ ] **Step 2：字段校验对齐 parser**

- name 正则 `^[a-z0-9][a-z0-9-]{0,62}$`
- description 长度 ≤1024 + 开头触发词检测（前端软提示）
- 超限红字提示，但最终校验仍在后端

- [ ] **Step 3：发布体验**

- 提交时展示 SKILL.md 预览
- 成功后跳转到详情页

- [ ] **Step 4：Commit**

```bash
git commit -am "feat(web): SkillForm 按新 frontmatter 规范重构表单分组与校验"
```

---

**Phase B 退出条件：** `SkillRequirementGate` 过滤生效、`SkillInstaller` 统一四来源入库、前端 CRUD + 启用/禁用/导入全通、`skills` 表首次有真实数据。

---

## Phase C · 自生成重写

### Task C.1：删除旧的生成/校验链路

**Files:**
- Delete: `src/main/java/com/lifepilot/skill/generation/SkillGenerator.java`
- Delete: `src/main/java/com/lifepilot/skill/validation/FormatValidator.java`
- Delete: `src/main/java/com/lifepilot/skill/validation/SecurityValidator.java`
- Delete: `src/main/java/com/lifepilot/skill/validation/SandboxValidator.java`
- Delete: `src/main/java/com/lifepilot/skill/validation/SkillValidationPipeline.java`
- Delete: 对应测试类（`SkillGenerator_*测试.java` 等）

- [ ] **Step 1：grep 引用确认可删**

```bash
grep -rn "SkillGenerator\|FormatValidator\|SecurityValidator\|SandboxValidator\|SkillValidationPipeline" src/
```

除被删文件外应只剩 `SkillAutoConfiguration` / `SkillGenerationTool` 引用点 —— 这两个下一步清理。

- [ ] **Step 2：删除文件 + 修 `SkillAutoConfiguration`**

```bash
rm src/main/java/com/lifepilot/skill/generation/SkillGenerator.java
rm src/main/java/com/lifepilot/skill/validation/FormatValidator.java
rm src/main/java/com/lifepilot/skill/validation/SecurityValidator.java
rm src/main/java/com/lifepilot/skill/validation/SandboxValidator.java
rm src/main/java/com/lifepilot/skill/validation/SkillValidationPipeline.java
```

`SkillAutoConfiguration` 去掉对应 Bean 定义。

- [ ] **Step 3：编译通过（预期 `SkillGenerationTool` 会报错）**

```bash
mvn -q compile
```

错误将由 Task C.3 修复（换成 SkillSynthesizer）。

- [ ] **Step 4：Commit（此次是暂时破坏性 commit，下一步立即修复）**

```bash
git commit -am "refactor(skill): 清理旧 SkillGenerator + 三件套校验器（将由 SkillValidator/SkillSynthesizer 替代）"
```

---

### Task C.2：`SkillValidator` 合一

**Files:**
- Create: `src/main/java/com/lifepilot/skill/validation/SkillValidator.java`
- Create: `src/test/java/com/lifepilot/skill/validation/SkillValidator_合一测试.java`

- [ ] **Step 1：写失败测试**

```java
class SkillValidator_合一测试 {

    @Test
    void 合法SKILL应通过() { /* ... */ }

    @Test
    void description违规应拒绝_带具体原因() { /* ... */ }

    @Test
    void body违规应拒绝_带具体原因() { /* ... */ }

    @Test
    void suggested_tools不存在应WARN不阻断_预置路径() { /* ... */ }

    @Test
    void suggested_tools不存在应ERROR_自生成路径() { /* ... */ }

    @Test
    void SKILL_md含常见secret模式应拒绝() {
        // 例：-----BEGIN PRIVATE KEY----- / api_key=.../ password=...
    }

    @Test
    void suggested_tools含HIGH风险工具_自生成路径应拒绝() { /* ... */ }
}
```

- [ ] **Step 2：写实现**

```java
package com.lifepilot.skill.validation;

import com.lifepilot.skill.MarkdownSkillParser.ParsedSkill;
import com.lifepilot.skill.spec.SkillFrontmatter;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import com.lifepilot.tool.model.ToolRiskLevel;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Skill 合一校验器 —— 合并原 FormatValidator / SecurityValidator / SandboxValidator。
 *
 * @author zsg
 * @since 2026-04-24
 */
@Component
public class SkillValidator {

    private static final List<Pattern> SECRET_PATTERNS = List.of(
            Pattern.compile("-----BEGIN [A-Z ]+PRIVATE KEY-----"),
            Pattern.compile("(?i)api[_-]?key\\s*[:=]\\s*['\"][a-zA-Z0-9_-]{20,}['\"]"),
            Pattern.compile("(?i)password\\s*[:=]\\s*['\"][^'\"]{4,}['\"]"),
            Pattern.compile("sk-[a-zA-Z0-9]{40,}")
    );

    private final SkillDescriptionValidator descriptionValidator;
    private final SkillBodyValidator bodyValidator;
    private final DynamicToolRegistry toolRegistry;

    public SkillValidator(SkillDescriptionValidator descriptionValidator,
                          SkillBodyValidator bodyValidator,
                          DynamicToolRegistry toolRegistry) {
        this.descriptionValidator = descriptionValidator;
        this.bodyValidator = bodyValidator;
        this.toolRegistry = toolRegistry;
    }

    /** 预置/导入/市场路径 —— suggestedTools 不存在只 WARN */
    public void validate(ParsedSkill parsed) {
        validateCommon(parsed);
        for (String toolId : parsed.frontmatter().zhiweiMeta().suggestedTools()) {
            if (!toolRegistry.hasTool(toolId)) {
                // 只记 WARN 日志（由上游 SkillInstaller 装饰器处理），不抛
            }
        }
    }

    /** 自生成路径 —— 额外严格约束 */
    public void validateGenerated(ParsedSkill parsed) {
        validateCommon(parsed);
        for (String toolId : parsed.frontmatter().zhiweiMeta().suggestedTools()) {
            if (!toolRegistry.hasTool(toolId)) {
                throw new IllegalArgumentException("自生成 skill 引用了未知工具: " + toolId);
            }
            var tool = toolRegistry.getTool(toolId).orElseThrow();
            if (tool.riskLevel() == ToolRiskLevel.HIGH || tool.riskLevel() == ToolRiskLevel.CRITICAL) {
                throw new IllegalArgumentException("自生成 skill 不得声明 HIGH/CRITICAL 风险工具: " + toolId);
            }
        }
    }

    private void validateCommon(ParsedSkill parsed) {
        SkillFrontmatter fm = parsed.frontmatter();
        descriptionValidator.validate(fm.description());
        bodyValidator.validate(parsed.body());
        for (Pattern p : SECRET_PATTERNS) {
            if (p.matcher(parsed.body()).find()) {
                throw new IllegalArgumentException("body 命中疑似 secret 模式: " + p.pattern());
            }
        }
    }
}
```

- [ ] **Step 3：Run passes**

- [ ] **Step 4：接入 `SkillInstaller`**

`SkillInstaller.install(...)` 改为调 `validator.validate(parsed)`；`SkillInstaller.installGenerated(...)` 调 `validator.validateGenerated(parsed)`。

- [ ] **Step 5：Commit**

```bash
git commit -am "feat(skill): SkillValidator 合一（description/body/secret/tool 风险一站式）"
```

---

### Task C.3：`SkillSynthesizer`

**Files:**
- Create: `src/main/java/com/lifepilot/skill/generation/SkillSynthesizer.java`
- Create: `src/main/java/com/lifepilot/skill/generation/SkillSynthesisContext.java`
- Create: `src/test/java/com/lifepilot/skill/generation/SkillSynthesizer_管线测试.java`

- [ ] **Step 1：`SkillSynthesisContext` record**

```java
package com.lifepilot.skill.generation;

import java.util.List;

public record SkillSynthesisContext(
        String gapDescription,
        String targetScenario,
        List<String> availableToolIds
) {}
```

- [ ] **Step 2：写失败测试**

```java
class SkillSynthesizer_管线测试 {

    @Test
    void 一次生成通过校验应直接落库() { /* mock LLM 返回合法 SKILL.md */ }

    @Test
    void 首次违规应迭代修正() { /* mock 第一次违规, 第二次合法 */ }

    @Test
    void 超过修正次数应抛出最后一次错误() { /* ... */ }

    @Test
    void 生成的skill应enabled_1_并发布SkillGeneratedEvent() { /* ... */ }
}
```

- [ ] **Step 3：写实现**

```java
package com.lifepilot.skill.generation;

import com.lifepilot.generation.router.GenerationRouter;
import com.lifepilot.skill.MarkdownSkillParser;
import com.lifepilot.skill.SkillConfigProperties;
import com.lifepilot.skill.event.SkillGeneratedEvent;
import com.lifepilot.skill.install.SkillInstaller;
import com.lifepilot.skill.install.SkillSourceType;
import com.lifepilot.skill.install.SkillInstallation;
import com.lifepilot.skill.validation.SkillValidator;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.nio.file.Paths;
import java.time.Instant;

/**
 * 基于 LLM 生成 SKILL.md → 校验 → 落库的管线。替代老 SkillGenerator。
 *
 * @author zsg
 * @since 2026-04-24
 */
@Service
public class SkillSynthesizer {

    private static final int MAX_FIX_ATTEMPTS = 2;

    private final GenerationRouter generationRouter;
    private final MarkdownSkillParser parser;
    private final SkillValidator validator;
    private final SkillInstaller installer;
    private final ApplicationEventPublisher publisher;
    private final SkillConfigProperties config;

    public SkillSynthesizer(GenerationRouter generationRouter,
                            MarkdownSkillParser parser,
                            SkillValidator validator,
                            SkillInstaller installer,
                            ApplicationEventPublisher publisher,
                            SkillConfigProperties config) {
        this.generationRouter = generationRouter;
        this.parser = parser;
        this.validator = validator;
        this.installer = installer;
        this.publisher = publisher;
        this.config = config;
    }

    public SkillInstallation synthesize(SkillSynthesisContext ctx) throws Exception {
        String generated = generate(ctx);
        Exception lastFailure = null;

        for (int attempt = 0; attempt <= MAX_FIX_ATTEMPTS; attempt++) {
            try {
                var parsed = parser.parse(generated);
                validator.validateGenerated(parsed);
                Path autoDir = Paths.get(config.getDirectory(), "auto");
                var install = installer.install(new SkillInstaller.InstallRequest(
                        SkillSourceType.AUTO_GENERATED,
                        "ai-generated",
                        null, generated, autoDir));
                publisher.publishEvent(new SkillGeneratedEvent(
                        install.name(), install.sourceType(), Instant.now()));
                return install;
            } catch (Exception e) {
                lastFailure = e;
                if (attempt == MAX_FIX_ATTEMPTS) break;
                generated = fix(generated, e.getMessage(), ctx);
            }
        }
        throw new SkillSynthesisException(
                "生成失败（" + (MAX_FIX_ATTEMPTS + 1) + " 次尝试）", lastFailure);
    }

    private String generate(SkillSynthesisContext ctx) { /* 调用 skill-synthesis.st 模板 + generationRouter */ return null; }
    private String fix(String skillMd, String errorMsg, SkillSynthesisContext ctx) { /* 调用 skill-fix.st */ return null; }
}
```

- [ ] **Step 4：Run tests pass**

- [ ] **Step 5：Commit**

```bash
git commit -am "feat(skill): SkillSynthesizer 新生成管线（校验失败迭代 + AUTO_GENERATED 落库 + 事件）"
```

---

### Task C.4：Prompt 模板改造

**Files:**
- Delete: `src/main/resources/prompts/generation/skill-generation-enhanced.st`
- Create: `src/main/resources/prompts/generation/skill-synthesis.st`
- Modify: `src/main/resources/prompts/generation/skill-fix.st`
- Modify: `src/main/resources/prompts/skill/gap-analysis.st`（轻改，产出 schema 对齐 `SkillSynthesisContext`）

- [ ] **Step 1：`skill-synthesis.st`**

内容骨架（遵从"抽象占位符"反 feedback 规则，`memory/feedback_prompt_abstract_not_concrete.md`）：

```
你是一个 Skill 作者，要产出一份符合 ZhiWei v2 规范的 SKILL.md。

## 规范摘要（硬约束）
- frontmatter 必需字段: name / description / version
- description: ≤1024 字符，以"当...时使用"或"Use when..."开头，**禁止写工作流步骤词**（步骤 1 / 首先 / 然后）
- body ≤5000 字符；必需小节: ## 适用场景 / ## 不适用场景 / ## 工作流
- 详细内容超长时应拆到 references/，body 里写"参见 {skill_dir}/references/xxx.md"

## 输入
- 场景描述: {{targetScenario}}
- 现有工具列表: {{availableToolIds}}
- 不足之处: {{gapDescription}}

## 输出格式
直接输出完整 SKILL.md（含 `---` 分隔的 frontmatter），不要多余解释。

## 示例结构（占位符，勿照抄）
---
name: <kebab-case-name>
description: 当 <某场景> 时使用。关键词: <k1>, <k2>, <k3>
version: 1.0.0
metadata:
  zhiwei:
    suggested_tools: [<tool-id-from-list>]
    tags: [<tag>]
    category: <category>
---
# <中文指南标题>

## 适用场景
- <场景 A>

## 不适用场景
- <反例 A>

## 工作流
1. <步骤 A>
```

- [ ] **Step 2：`skill-fix.st`**

```
上一次生成的 SKILL.md 校验失败。

## 失败原因
{{errorMessage}}

## 原始输出
{{previousSkillMd}}

## 任务
修正后重新输出完整 SKILL.md。保留可用部分，只改出错的地方。
```

- [ ] **Step 3：`gap-analysis.st` 产出 schema 对齐 `SkillSynthesisContext`**

```json
{
  "gap_description": "...",
  "target_scenario": "...",
  "available_tool_ids": [...]
}
```

- [ ] **Step 4：Commit**

```bash
git commit -am "feat(prompt): skill-synthesis.st 新模板 + skill-fix.st 对齐新校验信号"
```

---

### Task C.5：`SkillGeneratedEvent`

**Files:**
- Create: `src/main/java/com/lifepilot/skill/event/SkillGeneratedEvent.java`

- [ ] **Step 1：事件类**

```java
package com.lifepilot.skill.event;

import com.lifepilot.skill.install.SkillSourceType;

import java.time.Instant;

/**
 * Skill 自动生成完成事件。
 *
 * @author zsg
 * @since 2026-04-24
 */
public record SkillGeneratedEvent(String skillName, SkillSourceType sourceType, Instant at) {}
```

- [ ] **Step 2：SSE 广播监听器**

```java
@Component
public class SkillGeneratedSseBroadcaster {

    private final SseEmitterRegistry emitters;

    @EventListener
    public void onGenerated(SkillGeneratedEvent e) {
        emitters.broadcast("/api/skills/events", Map.of(
                "type", "SKILL_GENERATED",
                "skillName", e.skillName(),
                "sourceType", e.sourceType().name(),
                "at", e.at().toString()));
    }
}
```

（`SseEmitterRegistry` 如项目已有复用，没有则新增最小实现。）

- [ ] **Step 3：Commit**

```bash
git commit -am "feat(skill): SkillGeneratedEvent + SSE 广播器"
```

---

### Task C.6：前端 SSE 订阅 + toast

**Files:**
- Create: `zhiwei-web/src/composables/useSkillLifecycleEvents.ts`
- Modify: `zhiwei-web/src/App.vue` 或全局布局组件（启动订阅）

- [ ] **Step 1：`useSkillLifecycleEvents.ts`**

```typescript
import { onMounted, onUnmounted } from 'vue'
import { toast } from '@/composables/useToast'
import { useRouter } from 'vue-router'

export function useSkillLifecycleEvents() {
  const router = useRouter()
  let source: EventSource | null = null

  onMounted(() => {
    source = new EventSource('/api/skills/events')
    source.onmessage = (e) => {
      const data = JSON.parse(e.data)
      if (data.type === 'SKILL_GENERATED') {
        toast({
          title: `ZhiWei 已为你生成技能：${data.skillName}`,
          description: '点击查看详情',
          action: () => router.push(`/skills/${data.skillName}`),
        })
      }
    }
  })

  onUnmounted(() => source?.close())
}
```

- [ ] **Step 2：在全局布局挂载调用**

`App.vue` 或 `MainLayout.vue` 里：

```vue
<script setup lang="ts">
import { useSkillLifecycleEvents } from '@/composables/useSkillLifecycleEvents'
useSkillLifecycleEvents()
</script>
```

- [ ] **Step 3：`npm run build` 通过**

- [ ] **Step 4：Commit**

```bash
git commit -am "feat(web): Skill 生成事件订阅 + toast 通知"
```

---

### Task C.7：`SkillGapDetector` 轻改造

**Files:**
- Modify: `src/main/java/com/lifepilot/skill/generation/SkillGapDetector.java`

- [ ] **Step 1：解耦**

原逻辑：detect → 直接触发 generator。
新逻辑：detect → 产出 `GapSignal` 事件，**不直接生成**。生成由 `generate_skill` 工具（LLM 决定）或前端按钮（用户决定）触发。

```java
@Component
public class SkillGapDetector {
    // ... 保留 detect 逻辑 ...

    public void detectAndPublish(UserRequest req) {
        var gap = detect(req);
        if (gap.isPresent()) {
            publisher.publishEvent(new SkillGapDetectedEvent(gap.get()));
        }
    }
}
```

- [ ] **Step 2：`SkillGenerationTool` 对接 SkillSynthesizer**

```java
@Component
public class SkillGenerationTool {
    // execute(params) 改调 synthesizer.synthesize(new SkillSynthesisContext(...))
}
```

- [ ] **Step 3：编译 + 测试**

```bash
mvn -q test
```

- [ ] **Step 4：Commit**

```bash
git commit -am "refactor(skill): SkillGapDetector 解耦生成 + SkillGenerationTool 对接 Synthesizer"
```

---

**Phase C 退出条件：** 旧三件套删净、`SkillValidator` 合一、`SkillSynthesizer` 生成成功落库 `AUTO_GENERATED` + 发事件、前端能收到 toast 并跳转详情。

---

## Phase D · 26 个预置 Skill 迁移

### 迁移模板（以 `github-workflow` 为示例）

**旧 frontmatter**（当前 classpath 下）：
```yaml
---
id: github-workflow
name: GitHub 工作流
description: 管理 GitHub PR / Issue / CI
version: 1.0.0
suggested-tools:
  - gh.pr.create
  - gh.issue.list
---
```

**新 frontmatter**（迁移目标）：
```yaml
---
name: github-workflow
description: 当需要创建/合并/审查 PR、管理 Issue、查看 CI 状态时使用。关键词: pr, issue, ci, github, 拉取请求, 代码审查。不适用于纯本地 git 操作（用 shell.exec）
version: 2.0.0
metadata:
  zhiwei:
    suggested_tools: [gh.pr.create, gh.issue.list, gh.pr.review]
    tags: [github, ci, pr, issue]
    category: external-integration
    priority: normal
    requires:
      bins: [gh]
      env: [GITHUB_TOKEN]
      tools: [gh.pr.create]
---
```

**新 body 骨架**：
```markdown
# GitHub 工作流指南

## 适用场景
- 创建 / 合并 / 关闭 PR
- 审查 PR（查看 diff + 留言）
- 创建 / 查询 / 关闭 Issue
- 查看 CI 状态

## 不适用场景
- 纯本地 git 操作（commit / rebase / log） → 用 shell.exec
- 跨平台同步（Gitee/GitLab）→ 用对应的 skill

## 工作流
1. 查询当前仓库上下文（gh.repo.view）
2. 按场景调用具体动作
3. 遇参数不明时参见 `{skill_dir}/references/gh-commands.md`

## 详细参考
- gh CLI 完整参数：`{skill_dir}/references/gh-commands.md`
- 常见错误（认证/网络/权限）：`{skill_dir}/references/error-handbook.md`
- PR 模板：`{skill_dir}/assets/pr-template.md`
```

---

### Task D.1：批次 1 — 外部集成类（7 个）

**Skills:**
- `github-workflow`
- `gitee`
- `feishu`
- `browser-automation`
- `desktop-automation`
- `database-query`
- `api-debugger`

**每个 skill 步骤（重复 7 次）：**

- [ ] **Step 1：读老 SKILL.md**

```bash
cat src/main/resources/skills/github-workflow/SKILL.md
```

- [ ] **Step 2：按模板重写**
  - 删 `id` 字段，`name` 保留（目录名）
  - `description` 改写符合硬约束（当…时使用 / 关键词 / 反例）
  - `suggested-tools` → `metadata.zhiwei.suggested_tools`（字段名和列表格式变）
  - 加 `metadata.zhiwei.category: external-integration`
  - 加 `metadata.zhiwei.requires`（根据依赖添加 bins/env/tools）
  - body 补齐 3 小节
  - body > 5000 字符或含详细参数表 → 拆到 `references/`

- [ ] **Step 3：新建 references 目录**

```bash
mkdir -p src/main/resources/skills/github-workflow/references
# 将原 body 中详细 CLI 参数 / 错误处理 / 示例 挪到对应文件
```

- [ ] **Step 4：本地验证 parser 能吃**

```java
// 临时单元测试
@Test
void parse_github_workflow() {
    var content = Files.readString(Paths.get("src/main/resources/skills/github-workflow/SKILL.md"));
    assertThatCode(() -> new MarkdownSkillParser().parse(content)).doesNotThrowAnyException();
}
```

- [ ] **Step 5：Commit**

```bash
git add src/main/resources/skills/github-workflow/
git commit -m "refactor(skill): 迁移 github-workflow 到 v2 规范"
```

7 个 skill 每个一次独立 commit，便于 revert。

---

### Task D.2：批次 2 — 内容创作类（6 个）

**Skills:**
- `content-creator`
- `web-novel-writer`
- `summarizer`
- `research-assistant`
- `teaching-assistant`
- `doc-processor`

每个按 D.1 的流程迁移。`category: content-creation`。独立 commit。

---

### Task D.3：批次 3 — 自动化/编排类（5 个）

**Skills:**
- `code-assistant`
- `cron-scheduler`
- `workflow-creator`
- `daily-manager`
- `file-organizer`

`category: automation`。特别注意 `code-assistant`（197 行，必拆 references/）。独立 commit。

---

### Task D.4：批次 4 — 内置基础设施（8 个）

**Skills:**
- `datastore`
- `introspection`
- `healthcheck`
- `a2ui`
- `document-workspace`
- `log-analyzer`
- `data-analyst`
- `find-skills`

`category: infrastructure`。其中：
- `document-workspace/SKILL.md:7-11` 修正老注释（agent 调研报告 F.2 提到的 `core-tool-ids` 失效注释）
- `find-skills` 标 `priority: high`（元 skill）

独立 commit。

---

### Task D.5：新建 `skill-creator` 自举元 skill

**Files:**
- Create: `src/main/resources/skills/skill-creator/SKILL.md`
- Create: `src/main/resources/skills/skill-creator/references/frontmatter-reference.md`
- Create: `src/main/resources/skills/skill-creator/references/body-structure.md`

- [ ] **Step 1：写 SKILL.md**

```markdown
---
name: skill-creator
description: 当用户要创建新的 ZhiWei Skill 或修改已有 Skill 时使用。关键词: 创建技能, 新 skill, 编辑 skill, skill 规范。不适用于仅加载已有 skill（用 skill.load）
version: 1.0.0
metadata:
  zhiwei:
    tags: [skill, meta, authoring]
    category: infrastructure
    priority: high
    suggested_tools: [file.write, file.read]
---

# Skill 作者指南

## 适用场景
- 为新的场景创建 SKILL.md
- 修改已有 skill 的 frontmatter 或 body
- 审阅 skill 是否符合 v2 规范

## 不适用场景
- 加载已有 skill 指南（用 skill.load）
- 安装市场/导入的 skill（用前端或 SkillImportService）

## 工作流
1. 读规范：参见 `{skill_dir}/references/frontmatter-reference.md` / `body-structure.md`
2. 产出 SKILL.md 骨架（name / description / version / metadata.zhiwei / body 3 小节）
3. description 必须以"当...时使用"或"Use when..."开头，≤1024 字符，禁工作流词
4. 超长详细内容拆到 references/
5. 用 file.write 写到 `<skills 根>/<name>/SKILL.md`

## 详细参考
- frontmatter 字段完整说明：`{skill_dir}/references/frontmatter-reference.md`
- body 小节模板：`{skill_dir}/references/body-structure.md`
```

- [ ] **Step 2：写 references**

`frontmatter-reference.md` 内容来自 `docs/skill-spec.md` §2.1 / §2.2 展开（字段表 + 示例）。

`body-structure.md` 内容是四段式模板 + 各种 category 示例（external-integration / content-creation / automation / infrastructure）。

- [ ] **Step 3：Commit**

```bash
git add src/main/resources/skills/skill-creator/
git commit -m "feat(skill): 新增自举元 skill skill-creator（v2 规范的参考样本）"
```

---

### Task D.6：批次提交后回归

- [ ] **Step 1：启动应用，验证全部 27 个（26 + skill-creator）加载成功**

```bash
mvn spring-boot:run
# 查 log: "SkillDiscoveryRegistrar: 已安装 27 个 BUILTIN skill"
```

- [ ] **Step 2：查库**

```bash
sqlite3 ~/.zhiwei/zhiwei.db "SELECT COUNT(*) FROM skills WHERE source_type = 'BUILTIN';"
# 预期: 27
```

- [ ] **Step 3：catalog 端到端**

启动对话，随便问一个 skill 相关场景（例如"帮我审查一下 PR"），观察 LLM 是否正确 `skill.load(names=["github-workflow"])`。

---

**Phase D 退出条件：** 26 + 1 个 BUILTIN skill 全部按 v2 规范，parser 能正确加载，catalog 结构化输出。

---

## Phase E · E2E 冒烟 + 文档同步

### Task E.1：E2E 集成测试

**Files:**
- Create: `src/test/java/com/lifepilot/integration/SkillSystemRefactor_端到端集成测试.java`

- [ ] **Step 1：写集成测试**

```java
package com.lifepilot.integration;

/**
 * Skill 系统重构端到端集成测试。覆盖：
 * 1. BUILTIN skill 启动安装 + skills 表写入
 * 2. skill.load 工具激活 + activatedToolIds 合并
 * 3. enable/disable 切换影响 catalog
 * 4. AUTO_GENERATED 生成链路 + SkillGeneratedEvent 发布
 * 5. requires 未满足的 skill 不进 catalog
 * 6. .skill 包导入
 *
 * @author zsg
 * @since 2026-04-24
 */
@SpringBootTest
@AutoConfigureMockMvc
class SkillSystemRefactor_端到端集成测试 {

    @Test
    void 应在启动时安装全部builtin_skill() { /* ... */ }

    @Test
    void skill_load应合并activatedToolIds() { /* ... */ }

    @Test
    void 禁用skill后不应出现在catalog() { /* ... */ }

    @Test
    void requires未满足的skill应被过滤出catalog() { /* ... */ }

    @Test
    void 自生成skill成功应发布SkillGeneratedEvent() { /* ... */ }

    @Test
    void 导入skill包应正确入库与落盘() { /* ... */ }
}
```

- [ ] **Step 2：运行**

```bash
mvn -q test -Dtest=SkillSystemRefactor_端到端集成测试
```

- [ ] **Step 3：Commit**

```bash
git commit -am "test(skill): 端到端集成测试（BUILTIN/skill.load/enabled/requires/Generated/Import 六场景）"
```

---

### Task E.2：文档同步

**Files:**
- Modify: `docs/architecture/skill-system.md`
- Modify: `docs/features/skill-system.md`
- Modify: `docs/features/skill-development.md`

- [ ] **Step 1：`docs/architecture/skill-system.md` 重写**

反映新架构：
- 三级物理分层（SKILL.md / references/ / scripts/ / assets/）
- 激活路径（skill.load 唯一入口）
- skills 表职责（安装元数据事实源）
- 四来源（BUILTIN/USER_IMPORTED/MARKETPLACE/AUTO_GENERATED）
- 校验链（Parser → Description + Body + SkillValidator → RequirementGate）
- 生成链（Synthesizer + Event + 前端 toast）

- [ ] **Step 2：`docs/features/skill-system.md` 重写（用户视角）**

- 如何启用/禁用 skill
- 如何导入 .skill 包
- 如何从市场下载
- AUTO_GENERATED 是什么，何时产生

- [ ] **Step 3：`docs/features/skill-development.md` 重写（开发者视角）**

- 引用 skill-creator skill 作为开发入口
- 链接 `docs/skill-spec.md`
- 展示一个最小可用 SKILL.md 的模板

- [ ] **Step 4：Commit**

```bash
git commit -am "docs(skill): 三份 skill 文档对齐 v2 架构"
```

---

### Task E.3：和工具暴露 PR 合并冒烟

- [ ] **Step 1：Rebase 对齐 `develop`**

```bash
git fetch origin develop
git rebase origin/develop
```

解决冲突（预期集中在 `application.yml` / `SkillAutoConfiguration.java`）。

- [ ] **Step 2：手工冒烟清单**

- 启动后端 + 前端
- 用自然对话让 LLM 解决一个复杂场景（如"帮我写一个每日早报 skill"）
- 观察：
  - `skill.search`？还是直接根据 catalog 命中 content-creator？
  - 选中的 skill 是否触发 `skill.load`？
  - 激活后工具是否在下一轮可用？
  - 如果命中 gap，SkillSynthesizer 是否触发，前端是否收到 toast？
- 前端操作：启用/禁用 / 导入一个自造 .skill 包 / 查看 AUTO_GENERATED skill 详情

- [ ] **Step 3：推送 + 更新 PR**

```bash
git push -u origin feature/tool-exposure-refactor
# GitHub 上 PR 已存在（工具暴露 PR），追加本次 commits
# 更新 PR 描述，加上 Skill 系统重构小节
```

- [ ] **Step 4：不 merge，等人工 review**

---

**Phase E 退出条件：** E2E 集成测试绿、文档三份对齐新架构、冒烟清单全通、PR 已更新等 review。

---

## Self-Review

**1. 规范覆盖**
- ✅ Skill.md 三级分层（Phase 0 Task 0.1 规范文档 + Phase D 迁移落地）
- ✅ frontmatter 新字段（Phase 0 Task 0.5 records + 0.6 parser）
- ✅ description 硬约束（Phase 0 Task 0.7 Validator）
- ✅ body 硬约束（Phase 0 Task 0.8 Validator）
- ✅ V17 drop + recreate（Phase 0 Task 0.2）
- ✅ 安装元数据 DB（Phase 0 Task 0.3/0.4）
- ✅ 激活路径归一 `skill.load`（Phase A Task A.1-A.3）
- ✅ file.read 删 skill 捷径 + 路径白名单（Phase A Task A.6）
- ✅ Catalog 结构化 + enabled 过滤 + requires 过滤（Phase A Task A.7 + Phase B Task B.1）
- ✅ SkillDisclosureTool 清除（Phase A Task A.4）
- ✅ ReactAgentLoop.detectSkillToolActivation 清除（Phase A Task A.5）
- ✅ 死配置清除（Phase A Task A.9）
- ✅ 多渠道安装（Phase B Task B.2-B.5）
- ✅ 前端改造（Phase B Task B.7-B.8 + Phase C Task C.6）
- ✅ 自生成重写（Phase C Task C.1-C.7）
- ✅ 26 预置 skill 迁移（Phase D Task D.1-D.4）
- ✅ skill-creator 自举（Phase D Task D.5）
- ✅ E2E + 文档（Phase E Task E.1-E.2）
- ✅ 和工具暴露 PR 一起冒烟（Phase E Task E.3）

**2. Placeholder 扫描**
- 少量 TODO 在 `SkillImportService.importFromGitUrl()`（标为 phase B.4 后期，但作为 stub 不在本 PR 实际走），合理。
- `SkillSynthesizer.generate()` / `fix()` 未展开调用 GenerationRouter 细节：依赖现有 Router API，实现时参见 `prompts/generation/skill-synthesis.st`。
- `SseEmitterRegistry` 未展开：project 若无则新建最小实现（5-10 行）。

**3. 类型一致性**
- `SkillActivation(name, instructions, suggestedTools)` 贯穿 Phase A Task A.1（Executor 使用）+ Task A.2（Activator 返回）。✅
- `SkillInstallation` 字段在 Repository / Installer / Synthesizer / Controller 一致。✅
- `SkillSourceType` 四值（BUILTIN/USER_IMPORTED/MARKETPLACE/AUTO_GENERATED）全局统一。✅
- `SkillFrontmatter` / `SkillZhiweiMeta` / `SkillRequires` record 层次稳定，Parser 产出、Validator 消费、Installer 透传。✅

---

## 退出标准（整体）

- ✅ `mvn -q test` 全绿（新增约 15 个测试类，预期约 80+ 用例）
- ✅ `npm run build` + `npm run test:run` 前端全绿
- ✅ 启动后 `skills` 表有 27 行 BUILTIN 记录，`enabled=1`
- ✅ Catalog 在 system prompt 里呈现结构化 XML（手动 tail log 确认）
- ✅ `tools.search("skill")` 能命中 `skill.load` 工具
- ✅ `skill.load(names=[...])` 激活后下一轮 `activatedToolIds` 正确合并
- ✅ 禁用某 skill 后 catalog 不再出现该 skill，`skill.load` 返回错误
- ✅ 生成 AUTO_GENERATED skill 后前端收到 toast
- ✅ 导入 .skill 包流程端到端可用
- ✅ `feature/tool-exposure-refactor` 分支 rebase 完成，PR 描述更新
- ✅ `docs/skill-spec.md` / `docs/architecture/skill-system.md` 等 4 份文档齐

---
