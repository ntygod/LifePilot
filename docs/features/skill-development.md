# Skill 开发指南 — 特性说明

> **文档性质**：特性说明文档
> **模块归属**：`com.lifepilot.skill`
> **最后更新**：2026-03

## 1. 功能概述

知微支持两种 Skill 扩展方式：Markdown 声明式 Skill（推荐，已实现）和 Java 原生内置 Skill（📋 规划中）。用户通过在 `~/.zhiwei/skills/` 目录下创建 SKILL.md 文件夹即可定义自定义 Skill，系统自动热加载。此外，系统还能通过 LLM 自动检测能力缺口并生成新 Skill。

## 2. 核心特性

### 2.1 Markdown 声明式 Skill（推荐）

每个 Skill 以文件夹形式存在，文件夹内包含 `SKILL.md` 主定义文件和可选的 `references/` 子目录：

```
~/.zhiwei/skills/
  └── my-skill/
      ├── SKILL.md          # 主定义文件
      └── references/       # 可选参考文件
          └── example.md
```

`SKILL.md` 采用 YAML Frontmatter + Markdown Body 格式：
- YAML Frontmatter 定义元数据（id、name、description、version、suggestedTools、triggers 等）
- `triggers` 字段为 `List<String>`，用于系统提示词中的关键词匹配，帮助 Agent 快速发现相关 Skill
- Markdown Body 定义 Skill 指令（instructions），即激活后注入 Agent 上下文的专业指导

### 2.2 热加载

保存 SKILL.md 后系统自动检测变更并重新加载，无需重启：
- 新建文件夹：自动加载并注册
- 修改 SKILL.md：500ms 防抖后重新解析注册
- 删除文件夹：自动注销对应 Skill
- 解析失败：保留上一个有效版本，不影响已注册的 Skill

### 2.3 三重安全验证

所有用户定义和自动生成的 Skill 必须通过三阶段验证：

1. **格式验证**（FormatValidator）：校验 YAML Frontmatter 格式和必填字段
2. **安全验证**（SecurityValidator）：校验 suggestedTools 白名单、风险等级、预算上限，检测 Prompt 注入
3. **沙箱验证**（SandboxValidator）：在隔离环境中验证 Skill 定义的内部一致性

### 2.4 Java 原生内置 Skill — 📋 规划中（尚未实现）

> 以下 `BuiltinSkillProvider`、`@BuiltinSkill` 注解和 `BuiltinSkillRegistrar` 均为设计规划，当前版本尚未实现。

需要直接访问 Spring 生态、数据库操作或复杂业务逻辑时，可实现 `BuiltinSkillProvider` 接口：

- 实现 `provide()` 返回 `SkillDefinition` 蓝图
- 实现 `registerTools()` 注册工具到 `DynamicToolRegistry`
- 标注 `@BuiltinSkill(id = "xxx", order = n)` 注解
- 系统启动时由 `BuiltinSkillRegistrar` 自动扫描注册

### 2.5 LLM 驱动的 Skill 自生成

当用户请求超出现有 Skill 能力范围时，系统可自动生成新 Skill：

1. `SkillGapDetector` 检测能力缺口
2. `SkillGenerator` 调用 LLM 生成 SKILL.md 内容（利用 `SkillTemplateLibrary` 提供模板参考，`ToolCapabilityManifest` 提供可用工具清单）
3. 生成的 Skill 经过三重验证
4. 用户确认后持久化到 `~/.zhiwei/skills/auto/{skill-id}/SKILL.md`

自动生成的 Skill 默认 `userConfirmed=false`，必须用户确认后才能激活。

## 3. 使用场景

**场景一：创建自定义领域 Skill**

用户在 `~/.zhiwei/skills/code-review/` 目录下创建 `SKILL.md`，定义代码审查的专业指令和建议工具。保存后系统自动热加载，Agent 在后续对话中即可发现和激活该 Skill。

**场景二：系统自动补全能力**

用户请求"帮我分析这份财务报表"，系统检测到没有匹配的 Skill，自动生成财务分析 Skill。经验证和用户确认后，Skill 持久化并可在后续对话中复用。

**场景三：开发内置 Skill 插件（📋 规划中）**

开发者实现 `BuiltinSkillProvider` 接口，注册专业工具和 Skill 定义。通过 `@BuiltinSkill` 注解控制注册顺序，系统启动时自动加载。（此功能尚未实现）

## 4. 配置项

| 配置键 | 默认值 | 说明 |
|--------|--------|------|
| `lifepilot.skills.directory` | `~/.zhiwei/skills` | Skill 文件目录 |
| `lifepilot.skills.skill-filename` | `SKILL.md` | Skill 定义文件名 |
| `lifepilot.skills.hot-reload-debounce-ms` | `500` | 热加载防抖间隔（毫秒） |
| `lifepilot.skills.auto-generation.enabled` | `true` | 自生成功能开关 |
| `lifepilot.skills.validation.max-name-length` | `128` | 名称最大长度 |
| `lifepilot.skills.validation.max-instructions-length` | `10000` | 指令最大长度 |

## 5. 限制与未来方向

**当前限制**：
- Markdown Skill 不支持直接定义工具执行逻辑，只能通过 suggestedTools 引用已注册的工具
- 自动生成的 Skill 质量受 LLM 能力限制，复杂领域可能需要人工调整
- 热加载仅监听一级子目录，不支持嵌套目录结构

**未来方向**：
- Skill 模板市场：提供常用领域的 SKILL.md 模板
- Skill 版本管理：支持 Skill 的版本迭代和回滚
- 可视化 Skill 编辑器：通过 Web UI 创建和编辑 Skill
