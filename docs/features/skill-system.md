# Skill 系统 — 特性说明

> **文档性质**：特性说明文档
> **模块归属**：`com.lifepilot.skill`
> **最后更新**：2026-05-03

## 1. 功能概述

Skill 系统为知微提供程序性知识管理能力，让 Agent 能够按需获取专业领域的指令与工具建议。一个 Skill 是一个目录，包含一份 `SKILL.md` 主文件（含 YAML frontmatter 和 Markdown body）以及可选的 `references/` / `scripts/` / `assets/` 子目录。

v2 架构下，Skill 通过 **四来源** 统一管理——内置（BUILTIN）、用户导入（USER_IMPORTED）、市场下载（MARKETPLACE）、AI 自动生成（AUTO_GENERATED），所有来源都经过同一条安装流水线（解析 → 校验 → 写文件 → 登记元数据），通过 `skills` 表统一记录启用状态、版本、校验和、最后激活时间等元数据。Agent 运行时看到的技能目录，是"已启用 + 依赖满足 + 校验通过"的 skill 的交集，并由 Agent 通过统一工具 `skill.load` 按需激活（1-3 个/次）。

## 2. 核心特性

### 2.1 统一四来源安装

| 来源 | 说明 | 入口 |
|---|---|---|
| **BUILTIN**（内置）| 随知微安装包自带的 27 个预置 Skill | 启动时从 `classpath:skills/` 自动安装，落盘到用户 Skill 目录 |
| **USER_IMPORTED**（导入）| 用户上传的 `.skill` 压缩包 | 前端"导入技能"对话框 / `POST /api/skills/import` |
| **MARKETPLACE**（市场）| 从知微扩展市场下载的 Skill | 前端"从市场下载" / `POST /api/skills/install-from-marketplace` |
| **AUTO_GENERATED**（AI 生成）| Agent 判定能力缺口后 LLM 自动产出 | `SkillSynthesizer` 自生成，完成后通过 SSE 推送到前端 toast |

每条 skill 记录存在 `skills` 表，含 `enabled` 开关（前端可切换）、`source_type`（前端用徽章区分）、`checksum`（SHA-256 签名）、`installed_at` / `updated_at` / `last_activated_at` 时间戳。

### 2.2 三级物理分层与按需加载

一个 skill 的目录结构：

```
~/.zhiwei/skills/<name>/
├── SKILL.md              # 必需：L1 frontmatter + L2 骨架 body（≤5000 字符）
├── references/           # 可选：L3 按需加载的详细参考
│   └── *.md              #   LLM 用 file.read 主动加载
├── scripts/              # 可选：可执行脚本
└── assets/               # 可选：模板/schema/静态资源
```

Agent 运行时只看到 L1 frontmatter 生成的 **技能目录（skill catalog）** 摘要，不会一次性把所有 body 塞进上下文。只有被 `skill.load` 激活的 skill，其 body 才注入当前轮对话；super 详细的参考文件通过 `file.read` 按需拉取。

### 2.3 统一激活入口 `skill.load`

Agent 通过 BuiltinTool `skill.load(names=["skill-a", "skill-b"])` 激活 skill（一次 1-3 个）。激活时：

- SKILL.md 的 body 被裹入 `<skill name="X">...</skill>` 注入到本轮 userPrompt 头部
- body 中的占位符会被替换为真实路径：
  - `{skill_dir}` → skill 安装目录
  - `{skill_references_dir}` → `{skill_dir}/references`
  - `{skill_scripts_dir}` → `{skill_dir}/scripts`
- skill frontmatter 声明的 `suggested_tools` 被合并到 Agent 下一轮的可见工具集

与 v1 相比，v2 废除了 `file.read(skill=...)` 捷径和 `SkillDisclosureTool` 空壳——激活路径只此一条，契约清晰。

### 2.4 校验链与依赖门控

**硬约束（写入表前）**：

- `description` ≤ 1024 字符、必须以"当…/用于…/Use when…"开头、不得含工作流词（步骤 N / 首先 / 然后 / Step N / First / Then）
- `body` ≤ 5000 字符、必须包含 `## 适用场景` / `## 不适用场景` / `## 工作流` 三个小节
- 正文不得含疑似 secret（PEM 私钥 / API key / sk- 开头 token 等）

**软约束（BUILTIN / USER_IMPORTED / MARKETPLACE）**：

- `suggested_tools` 引用的工具不存在时只记 WARN，不阻断

**严格约束（AUTO_GENERATED）**：

- `suggested_tools` 必须全部已注册
- 禁止引用 HIGH / CRITICAL 风险工具（防自生成绕过确认执行高危操作）

**运行期门控（`SkillRequirementGate`）**：

skill 若声明了 `metadata.zhiwei.requires`，加载期会检查 `bins`（可执行程序是否在 PATH）/ `env`（环境变量是否非空）/ `os`（匹配 `windows / darwin / linux`）/ `tools`（依赖的工具 id 是否已注册），任一不满足则该 skill 不进入技能目录。

### 2.5 Markdown 热加载

用户目录 `~/.zhiwei/skills/` 下新建或修改 `<name>/SKILL.md`，系统通过 `WatchService` 实时感知（默认 500ms 防抖），重新解析并刷新注册表；删除目录自动注销；解析失败时保留上一个有效版本，不影响已注册的 skill。

### 2.6 LLM 驱动的 Skill 自生成与前端通知

当 Agent 判定现有 skill 不足以完成任务时，`SkillSynthesizer` 会：

1. 渲染 `generation/skill-synthesis` prompt，调 LLM 生成 SKILL.md 原文
2. 做严格校验（description + body 硬约束 + secret 扫描 + 工具风险校验）
3. 失败时渲染 `generation/skill-fix` prompt 迭代修正（最多 2 次）
4. 通过 `SkillSynthesizer` 把成品写到 `<skills.directory>/auto/<name>/SKILL.md`，`source_type = AUTO_GENERATED`，`enabled = true`
5. 发布 `SkillGeneratedEvent`，经 `SkillGeneratedSseController` SSE 广播到前端，触发 toast 提示

前端通过 `/api/skills/events` SSE 连接订阅，收到后在"技能目录"刷新并显示"AI 为你准备了 X 技能"的提示。

## 3. 使用场景

**场景一：Agent 按需激活专业 skill**

用户请求"帮我创建一个每天早上 8 点的提醒任务"，Agent 在 system prompt 里看到 `<skill_catalog>` 中有 `cron-scheduler` 条目，调用 `skill.load(names=["cron-scheduler"])` 加载其 body 指令与建议工具，据此精准创建定时任务。

**场景二：用户自定义领域 skill**

用户在前端点击"新建技能"，按 v2 规范填写 SKILL.md（可参考预置的 `skill-creator`），保存后系统自动走安装流水线——校验 → 写到 `~/.zhiwei/skills/<name>/SKILL.md` → 登记到 `skills` 表，同时通过热加载注册到内存。Agent 后续对话即可发现并激活该 skill。

**场景三：从市场安装 skill**

用户在前端"导入技能"对话框切到"市场"Tab，搜索关键词后点击下载，系统从扩展市场拉取 zip/单文件 SKILL.md，走统一安装流水线写到 `skills` 表，`source_type = MARKETPLACE`，前端用徽章区分来源。

**场景四：AI 自动补全缺失能力**

用户请求"帮我分析这份财务报表"，Agent 判定现有 skill 不足，`SkillSynthesizer` 自动生成 `financial-report-analyzer`，经严格校验通过后落库并 SSE 通知前端：toast 弹出"AI 为你准备了财务分析技能"，用户在"技能目录"里看到新增的 skill（徽章标记"AI 生成"）。

## 4. 配置项

| 配置键 | 默认值 | 说明 |
|---|---|---|
| `lifepilot.skills.enabled` | `true` | Skill 系统总开关 |
| `lifepilot.skills.directory` | `${zhiwei.data-dir}/skills` | Skill 文件目录 |
| `lifepilot.skills.hot-reload-debounce-ms` | `500` | 热加载防抖间隔（毫秒）|
| `lifepilot.skills.skill-filename` | `SKILL.md` | Skill 定义文件名 |
| `lifepilot.skills.auto-generation.enabled` | `true` | 自生成功能开关 |
| `lifepilot.skills.auto-generation.max-validation-iterations` | `2` | 自生成迭代修正次数上限 |
| `lifepilot.skills.search.default-top-k` | `10` | Skill 语义搜索默认返回数量 |

## 5. 限制与未来方向

**当前限制**：

- 一次 `skill.load` 最多 3 个 skill，避免一次性注入过多上下文。
- body 硬限 5000 字符，超长必须拆到 `references/`；校验器不会自动拆分。
- 用户导入仅支持 `.skill` 压缩包上传，不支持 Git URL 直接拉取（如需从远端获取，可使用 SkillHub 市场或手动下载后导入）。
- `SkillRequirementGate` 中 `bins` 探测通过 `bin --version` 在 2 秒内成功退出判定，部分不支持 `--version` 的工具可能误判。
- AUTO_GENERATED skill 默认 `enabled = true` 立即生效，不做额外用户确认；安全完全依赖 `validateGenerated` 的严格规则。

**未来方向**：

- `bins` 探测升级为真正的 PATH 搜索，避免 `--version` 误判。
- Skill 执行效果评估（激活后任务完成率），反哺 catalog 排序。
- Skill 组合与依赖：一个 skill 声明它依赖另一个 skill，激活时一并加载。
