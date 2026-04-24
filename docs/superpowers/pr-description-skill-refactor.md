# Skill 系统 v2 重构

> 本 PR 在 `feature/tool-exposure-refactor` 分支上继续工具暴露重构之后的 Skill 系统重构。本次 Skill 重构部分为 47 个 commit / 176 个文件 / +9,912 / -7,121 行（基于 plan commit `3517200d` 之后）。

## 概述（3 分钟版本）

把 ZhiWei 的初级 Skill 系统升级为对齐业界（AgentSkills spec + openclaw）的渐进式 Skill 架构。做了四件事：

1. **SKILL.md 三级物理分层**：`frontmatter`（L1 元数据） / `body 骨架`（L2 流程） / `references / scripts / assets`（L3 按需加载）。
2. **激活路径归一到 `skill.load` 工具**：废除 `file.read(skill=…)` 捷径和 `SkillDisclosureTool` 空壳；`detectSkillToolActivation()` 黑魔法被删掉，改由 `ToolExecutionCoordinator` 统一合并 `activated_tool_ids`。
3. **`skills` 表作为多渠道安装状态的唯一事实源**：V21 迁移 drop + recreate，只存安装元数据；四种来源 `BUILTIN / USER_IMPORTED / MARKETPLACE / AUTO_GENERATED` 走同一 `SkillInstaller` 流水线。
4. **自生成链路重写**：`SkillSynthesizer` 替代 `SkillGenerator`；校验器从三件套（Format / Security / Sandbox）合一到 `SkillValidator`；生成成功发 `SkillGeneratedEvent` → SSE 广播 → 前端 toast。

伴随 27 个预置 Skill 全部迁移到 v2 规范（frontmatter + metadata.zhiwei 块 + body 骨架），新增自举样本 `skill-creator`。

---

## 主要变更

### 数据模型与校验层

**新增 records / enums**
- `SkillFrontmatter` / `SkillZhiweiMeta` / `SkillRequires` / `SkillPriority`：v2 frontmatter 的类型化视图
- `SkillInstallation` / `SkillSourceType`：安装状态记录 + 四来源枚举

**Parser 重写**
- `MarkdownSkillParser` 重写为 v2 规范，拒绝旧 `id` 字段（必须 `name`）
- 解析 `metadata.zhiwei` 嵌套块（requires / suggested_tools / tags / category / priority）
- 旧版 `skill/markdown/MarkdownSkillParser.java` 删除

**校验层重建**
- 新增 `SkillDescriptionValidator`（描述工程硬约束：≤1024 字、触发动词开头、禁工作流词）
- 新增 `SkillBodyValidator`（骨架必需小节 + body ≤5000 字符）
- 新增 `SkillValidator`（合一：安全/format/risk 一站式）
- 新增 `SkillRequirementGate`（加载期按 bins/env/os/tools 硬过滤）
- **删除** `FormatValidator` / `SandboxValidator` / `SecurityValidator` / `SkillValidationPipeline` / `SkillValidationResult`

**数据库迁移**
- V21：drop + recreate `skills` 表，新结构只存安装元数据（`source_type` / `source_uri` / `file_path` / `version` / `enabled` / `marketplace_id` / `checksum` / `installed_at` / `updated_at` / `last_activated_at`），单个复合索引 `idx_skills_source_enabled`
  > 本地提交时占的版本号是 V17，分支上已被 project-core PR 占用，合入 develop 时自动 rename 到 V21；迁移文件头部注释仍写"V17"需后续人工对齐。

### 运行时与激活层

- 新增 BuiltinTool `skill.load`（`SkillLoadTool` + `SkillLoadToolExecutor`）
  - 作为 Skill 系统唯一激活入口；一次最多 3 个
  - 未知 / disabled 的 skill 直接 400 类错误；已 enabled 的激活后返回 instructions + 合并 `activated_tool_ids`
  - 注册到 Tier 1 pinned，永远随 Tier 1 下发
- `SkillActivator` 升级：返回值含 `skillDir` / `referencesDir`；替换 `{skill_dir}` / `{skill_references_dir}` / `{sibling_dir}` 3 个占位符；`canActivate` 对 `userConfirmed` 的检查废弃，统一用 `enabled` 列查表
- `ReactAgentLoop.detectSkillToolActivation()` 删除；`mcp:` 前缀硬解析删除
- `ToolExecutionCoordinator` 补 `activated_tool_ids` 合并入口
- `ContextAssembler.buildSkillCatalog()` 改查 `SkillInstallationRepository.findByEnabled(true)` + RequirementGate 过滤 + category 分组 + priority 排序，输出结构化 XML
- `file.read` 删除 `skill` 参数捷径 + 新增 `SkillPathWhitelist` 防 path traversal
- 模板：`skill-catalog.st` 重写为结构化 XML；`react-system.st` 的 `<tool_protocol>` 段更新（Tier 1 → skill.load → tools.search → 兜底）

### 多渠道安装

- 新增 `SkillInstaller`（四来源统一）：`parse → validate → writeFile → upsert`，任一步失败短路，失败不残留
- 新增 `SkillImportService`：`.skill` 压缩包导入（含 zip slip + zip bomb 防护 + aux 文件复制）
- 新增 `SkillMarketplaceInstaller`：市场索引对接 + ClawHub 下载通道
- 新增 `SkillInstallationRepository`：JdbcTemplate CRUD + findByEnabled / findBySourceType / setEnabled / updateChecksum
- `SkillDiscoveryRegistrar` 改写：BUILTIN 路径从 classpath 扫描 → `SkillInstaller.install(source=BUILTIN)`；并重接新 parser
- `SkillController` 重构 REST 接口（见"Breaking changes"）

### 自生成链路

- 新增 `SkillSynthesizer`（替代 `SkillGenerator`）
  - 流程：LLM 生成 → 校验 → 失败带错误反馈迭代 → 成功 `upsert` AUTO_GENERATED 入库 → 发 `SkillGeneratedEvent`
- 新增 records：`SkillSynthesisContext` / `SkillSynthesisException`
- **删除** `SkillGenerator` / `SkillGapDetector` / `SkillGap` / `SkillTemplate` / `SkillTemplateLibrary` / `ToolCapabilityManifest`
- 新增 `SkillGeneratedEvent` ApplicationEvent
- 新增 `SkillGeneratedSseController` 端点 `GET /api/skills/events`（SSE）
- 新增 Prompt：`skill-synthesis.st`（替代旧 `skill-generation-enhanced.st`）+ `skill-fix.st` 对齐新校验信号

### 前端

- `SkillManageView` 精简：215 行下降至 215/改造；改用启用开关 + 来源徽章 + 导入按钮，删除旧 `SkillForm.vue`
- 新增 `SkillSourceBadge`（四来源徽章）
- 新增 `SkillInstallDialog`（从 `.skill` 包 / 市场导入）
- 新增 `SkillEditor`（软校验 + 预览）
- 新增 `SkillCreateDialog`（新建流程）
- 新增 `skillValidation.ts` + 单测（前端校验与后端对齐）
- 新增 `useSkillLifecycleEvents.ts` + 单测（订阅 SSE + 发 toast）
- `stores/skill.ts` 对齐新 API（字段 `id → name` 等）
- `types/index.ts` 新增 `SkillSourceType` / `SkillInstallation` 等

### 预置 Skill 迁移（27 个 BUILTIN 全部 v2）

按类别分 4 批：
- 外部集成类 7 个：`feishu` / `gitee` / `github-workflow` / `teaching-assistant` / `database-query` / ...
- 内容创作类 6 个：`content-creator` / `web-novel-writer` / `summarizer` / `data-analyst` / ...
- 自动化类 5 个：`cron-scheduler` / `workflow-creator` / `daily-manager` / `doc-processor` / ...
- 基础设施类 7 个 + 新增 `skill-creator`：`a2ui` / `api-debugger` / `find-skills` / `healthcheck` / `introspection` / `log-analyzer` / `research-assistant`

统一：都按新 frontmatter 规范 + metadata.zhiwei 块 + body 小节骨架 + 抽出 `references/` 子目录（如 `browser-automation/references/browser-actions.md`、`code-assistant/references/orchestration.md`）。

### 死代码清理

明确删除：
- `SkillDisclosureTool`（空壳）+ 测试
- `SkillGenerator` / `SkillGapDetector` / `SkillGap` / `SkillTemplate` / `SkillTemplateLibrary` / `ToolCapabilityManifest` + 测试
- `FormatValidator` / `SandboxValidator` / `SecurityValidator` / `SkillValidationPipeline` / `SkillValidationResult` + 测试
- `ReactAgentLoop.detectSkillToolActivation()`
- `FileReadToolExecutor.executeSkillRead()` + `skill` 参数
- `application.yml`：`meta.skill-discovery.skill-paths` 21 条死配置 + `core-tool-ids` 过期注释
- `prompts/generation/skill-generation-enhanced.st`
- 旧 `SkillActivationException`

---

## 测试覆盖

新增 / 改写测试（节选）：

**后端**
- `MarkdownSkillParser_新规范测试`（拒绝旧 id、解析 metadata.zhiwei、占位符识别）
- `SkillDescriptionValidator_硬约束测试`（长度 / 开头 / 禁词）
- `SkillBodyValidator_小节测试`（小节结构 + 字数）
- `SkillValidator_合一测试`（安全 + format + 风险 统一出口）
- `SkillRequirementGate_门控测试`（bins / env / os / tools 过滤）
- `SkillLoadToolExecutor_激活测试`（激活 / 占位符 / 未知 / disabled / ≤3 上限）
- `SkillPathWhitelist_白名单测试`（路径遍历防护）
- `SkillInstallationRepository_持久化测试`
- **`SkillSystemRefactor_端到端集成测试`**（6 场景：BUILTIN 启动安装 / skill.load 合并 activated_tool_ids / disabled 抛异常 / 未知抛异常 / ≤3 上限 / AUTO_GENERATED 入库后可激活）

**前端**
- `skillValidation.spec.ts`（8 个 case，前端校验规则）
- `useSkillLifecycleEvents.spec.ts`（5 个 case，SSE 订阅 + toast）

---

## Breaking Changes

- **`file.read` 的 `skill` 参数删除**：调用方必须走 `skill.load` 工具（或直接用 `path` 参数读 SKILL.md 物理路径）
- **POST `/api/skills` 签名变更**：改为按 `SkillInstallation` 新记录接收
- **PUT `/api/skills/{name}` 返回 501**：编辑流程改走"新建版本 + 启用/禁用"组合，避免变更原地写入
- **前端字段 `id → name`**：Skill 一级标识从自增 id 改为自然 name；API 响应 `{ items, total, page, pageSize }` 不变但每项结构变
- **`skills` 表 schema 重建**：V21 drop + recreate（旧表无写入方，历史数据可直接丢弃）
- **新 REST 端点**：
  - `PUT /api/skills/{name}/enable`（启用/禁用开关）
  - `POST /api/skills/import`（本地 `.skill` 包）
  - `POST /api/skills/install-from-marketplace`（市场）
  - `GET /api/skills/events`（SSE，Skill 生成事件）
- **`meta.skill-discovery.skill-paths` 死配置删除**：有自定义该配置的部署需手工清理

---

## 如何验证

### 启动
```bash
cd D:/WorkSpace/Project/News
mvn spring-boot:run
cd zhiwei-web && npm run dev
```

### 启动日志关键信号
- `Skill: 安装 27 个 BUILTIN skill ...`
- `BuiltinTool 注册：skill.load ... pinned=true`
- `Flyway: Migrating schema ... to V21 - skill system refactor`

### 典型操作
1. 新建会话让 LLM 处理复杂需求 →
   - 期望看到 `skill.load` 在 Tier 1，LLM 选中 1-3 个 skill 激活
   - 激活后下一轮 `activated_tool_ids` 正确注入 Tier 2
2. Skill 管理页：
   - 启用/禁用开关切换 → 刷新后 catalog 更新
   - 从 `.skill` 包导入 → 落盘 + 入表 + 出现在列表
3. 让 LLM 处理一个"无现成 skill"场景 → 观察 `SkillSynthesizer` 是否生成 AUTO_GENERATED skill → 前端是否收到 toast

---

## 已完成 / 留给未来 PR

**已完成**
- Phase 0 规范 + 解析 + 校验 + 仓库
- Phase A 激活路径重构（skill.load + Activator + detectSkillToolActivation 清除 + FileRead 清理）
- Phase B requires 门控 + 多渠道安装 + 前端
- Phase C 自生成重写（Synthesizer + Validator 合一 + 事件 + SSE + 前端 toast）
- Phase D 27 个预置 Skill 全部迁移
- Phase E.1 端到端集成测试
- Phase E.2 文档同步（`docs/skill-spec.md` / `docs/architecture/skill-system.md` / `docs/features/skill-system.md` / `docs/features/skill-development.md` 以及顶层 `ARCHITECTURE.md` / `FEATURES.md` / `API_ENDPOINTS.md`）

**留给未来 PR**
- **C.7 `SkillGapDetector` 重建**：原 detector 被删,下一代基于新 frontmatter / tool tag 匹配的 gap 检测尚未落地,目前自生成需 LLM 自行判断 gap
- **Git URL 导入**：`SkillImportService.importFromGitUrl()` 目前是 stub(本 PR 不走)
- **市场签名校验**：`SkillMarketplaceInstaller` 下载走 checksum 校验,未做签名验证
- **风险扫描前置**：安装前跑外部静态扫描(如 bins 危险调用检测)尚未接
- **`skill_audit_logs.skill_id` 语义对齐**:`skills` 表主键由 `id` 改为 `name` 后,审计日志的 `skill_id` 字段是否应同步 rename 尚未决定(旧数据兼容风险,本 PR 不动)
- **V21 迁移文件头部注释同步**：文件名从 V17 被 rename 到 V21 后,文件顶部注释仍写"V17",需人工改写

---

## 相关 Plan

- `docs/superpowers/plans/2026-04-24-skill-system-refactor.md` — 本次重构完整实施计划(Phase 0 → E)
- `docs/skill-spec.md` — SKILL.md 元规范(新建)
