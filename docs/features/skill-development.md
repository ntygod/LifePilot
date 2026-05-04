# Skill 开发指南 — 特性说明

> **文档性质**：开发者视角的特性说明
> **模块归属**：`com.lifepilot.skill`
> **最后更新**：2026-04-24

## 1. 开发起点

- **规范文档**：[`docs/skill-spec.md`](../skill-spec.md) 是 SKILL.md 的正式元规范（frontmatter 字段表、body 结构、校验规则）。
- **自举样本**：预置 skill `skill-creator`（`src/main/resources/skills/skill-creator/SKILL.md`）本身就是 v2 规范的参考样例——读它学 v2 写法最快。
- **架构全貌**：详见 [`docs/architecture/skill-system.md`](../architecture/skill-system.md)。

知微通过 Markdown 声明式 Skill 扩展 Agent 能力。每个 skill 是一个目录，目录名即 skill 的 `name`，路径形如 `~/.zhiwei/skills/<name>/`（预置 skill 从 classpath 提取到同一目录）。目录根下必须有 `SKILL.md`，可以选配 `references/` / `scripts/` / `assets/` 三个子目录。

## 2. 目录结构

```
~/.zhiwei/skills/<name>/
├── SKILL.md              # 必需：L1 frontmatter + L2 骨架 body
├── references/           # 可选：详细参考，LLM 按需 file.read 加载
│   └── *.md
├── scripts/              # 可选：可执行脚本（不入 context）
│   └── *.{sh,py,js}
└── assets/               # 可选：模板、schema、静态资源（不入 context）
    └── *
```

- **SKILL.md** 硬限 5000 字符，只写"骨架"——超长详细内容拆到 `references/`。
- **references/** 里是普通 Markdown，SKILL.md body 用自然语言引用（如 "参见 `{skill_dir}/references/api.md`"），LLM 运行时自行决定是否 `file.read`。
- **scripts/assets/** 不会注入 context，仅供 skill body 引用后由 Agent 调用对应工具执行。

## 3. 最小可用 SKILL.md 模板

```markdown
---
name: my-skill
description: 当用户需要...时使用。关键词：A、B、C。反例场景 → 用其他 Skill
version: 1.0.0
metadata:
  zhiwei:
    category: automation
    priority: normal
    tags: [keyword1, keyword2]
    suggested_tools:
      - tool.id1
      - tool.id2
    requires:
      bins: []
      env: []
      os: []
      tools: []
---

# <中文标题>

## 适用场景
- 场景一：...
- 场景二：...

## 不适用场景
- 反例一：... → 用 <其他 skill>
- 反例二：...

## 工作流
1. 第一步高层动作
2. 第二步高层动作（细节参见 {skill_dir}/references/details.md）
3. ...

## 详细参考（可选）
- 参数详表：{skill_dir}/references/api.md
- 常见错误：{skill_dir}/references/errors.md
```

## 4. Frontmatter 字段

| 字段 | 必需 | 约束 |
|---|---|---|
| `name` | 是 | 正则 `^[a-z0-9][a-z0-9-]{0,62}$`，必须等于目录名 |
| `description` | 是 | ≤1024 字符；以 "当…" / "用于…" / "Use when…" / "Use this when…" 开头；**不得含工作流词**（步骤 N / 首先 / 然后 / 接下来 / Step N / First / Then）|
| `version` | 是 | 语义化版本，如 `1.0.0` |
| `metadata.zhiwei.tags` | 否 | `List<string>`，辅助检索 |
| `metadata.zhiwei.suggested_tools` | 否 | `List<string>`，UI 展示、检索和人工参考元数据；不会自动注入工具 |
| `metadata.zhiwei.requires.bins` | 否 | `List<string>`，运行依赖的二进制（如 `git`, `gh`）|
| `metadata.zhiwei.requires.env` | 否 | `List<string>`，必需环境变量名（不含值）|
| `metadata.zhiwei.requires.os` | 否 | OS 白名单 `windows` / `darwin` / `linux` |
| `metadata.zhiwei.requires.tools` | 否 | `List<string>`，必需已注册工具 id |

字段 `id:`（v1 规范）以及旧 `category` / `priority` 已废弃——`MarkdownSkillParser.parse` 会直接拒绝 `id:`。

## 5. body 约束

硬规则（`SkillBodyValidator`，见 `src/main/java/com/lifepilot/skill/validation/SkillBodyValidator.java`）：

- ≤5000 字符
- 必须包含三个小节标题（**完全一致**）：`## 适用场景` / `## 不适用场景` / `## 工作流`

占位符（激活时由 `SkillActivator.resolvePlaceholders` 替换为绝对路径）：

- `{skill_dir}` → skill 安装目录
- `{skill_references_dir}` → `{skill_dir}/references`
- `{skill_scripts_dir}` → `{skill_dir}/scripts`

## 6. 安装方式

### 6.1 前端 UI（推荐）

- **新建**：前端"技能目录"页面 → 点击"新建技能" → `SkillEditor` 提供客户端软校验 → 后端 `SkillInstaller` 执行硬校验
- **导入 .skill 包**：点击"导入技能" → "上传"Tab → 选择 `.skill` / `.zip`（根目录含 SKILL.md）→ 后端自动解压 + 安装
- **从市场下载**：导入对话框的"市场"Tab → 搜索 → 下载

对应后端端点：

| 端点 | 说明 |
|---|---|
| `POST /api/skills` | 新建（JSON body 含 SKILL.md 原文）|
| `POST /api/skills/import` | 上传 `.skill` 压缩包 |
| `POST /api/skills/install-from-marketplace` | 从市场下载 |
| `PUT /api/skills/{name}/enabled` | 切换启用状态 |
| `GET /api/skills/events` | SSE 订阅自生成事件 |

### 6.2 直接写文件（热加载）

在 `~/.zhiwei/skills/<name>/SKILL.md` 下放文件，`SkillFileWatcher` 500ms 防抖后自动加载。此路径不走 `skills` 表 upsert（表记录由 `SkillInstaller` 维护），所以前端 UI 的启用开关和徽章不会显示这类"纯文件"skill——推荐仍通过 UI 新建，确保表与文件一致。

## 7. 校验错误诊断

| 错误消息 | 触发条件 | 修法 |
|---|---|---|
| `字段 'id' 已废弃，请用 'name'` | frontmatter 含老 `id` 字段 | 改为 `name:` |
| `name 必须匹配正则 ^[a-z0-9][a-z0-9-]{0,62}$` | name 含大写/下划线/中文等 | 用 kebab-case 小写 |
| `description 长度超过 ≤1024 字符限制` | description 过长 | 删除赘述，核心一句话 + 关键词 |
| `description 必须以 '当...' / '用于...' / 'Use when...' 开头` | 起手触发词不对 | 改写开头 |
| `description 不得含工作流词（步骤/首先/然后/Step N/First/Then）` | 描述里写了流程 | 流程写到 body `## 工作流`，描述只说"什么时候用" |
| `body 长度超过 ≤5000 字符限制` | body 过长 | 拆到 `references/`，body 只留骨架 |
| `body 缺少必需小节: ## 适用场景` / 等 | 三个必需小节缺一或标题拼写错 | 标题必须完全一致，前导 `## ` 不可丢 |
| `body 命中疑似 secret 模式` | 文本含 `BEGIN PRIVATE KEY` / `api_key = "..."` / `sk-...` 等 | 删除或脱敏 |
| `自生成 skill '...' 引用了未知工具` | AUTO_GENERATED 路径 `suggested_tools` 写了不存在的工具 | 只引用 `DynamicToolRegistry` 已注册的 tool id |
| `自生成 skill 不得声明 HIGH/CRITICAL 风险工具` | AUTO_GENERATED 引用了高危工具 | 自生成路径只能走 LOW/MEDIUM，手写 skill 不受此限 |

BUILTIN 和 USER_IMPORTED 路径 `suggested_tools` 引用未知工具只 WARN 不阻断（真正的工具可用性由 `SkillRequirementGate` 在 `requires.tools` 层做硬过滤）。

## 8. 常见陷阱

- **目录名必须等于 `name` 字段**：`SkillInstaller` 按 frontmatter 的 `name` 推导安装路径，手动调整目录名不会同步 skills 表。
- **`category` 五个值是约定而不是硬约束**：其他值不会拒绝，但 catalog 会原样渲染出奇怪的分组。建议沿用 `external-integration` / `content-creation` / `automation` / `infrastructure` / `utility`。
- **`suggested_tools` 软约束**：不存在的工具 id 在 BUILTIN / USER_IMPORTED / MARKETPLACE 路径只 WARN，但 `requires.tools` 中的依赖必须实际可用——否则 skill 直接从 catalog 剔除。
- **热加载保留最后有效版本**：修改 SKILL.md 解析失败时，系统不会把已有 skill 注销，而是保留上一次成功解析的版本；日志中会看到 WARN 记录失败原因。
- **BUILTIN 覆盖策略**：`SkillDiscoveryRegistrar` 检查文件存在即跳过，已存在不覆盖。升级知微时如需刷新某个预置 skill，需手动删除用户目录下对应文件夹再重启。

## 9. 深入阅读

- [`docs/skill-spec.md`](../skill-spec.md) — 正式规范
- [`docs/architecture/skill-system.md`](../architecture/skill-system.md) — 架构设计
- `src/main/resources/skills/skill-creator/` — 自举样本与参考文件
- `src/main/java/com/lifepilot/skill/validation/` — 校验器源码（错误消息的定义在这里）
