# 预置 Skill — 特性说明

> **文档性质**：特性说明文档
> **模块归属**：`com.lifepilot.meta.convenience` / `com.lifepilot.skill`
> **最后更新**：2026-05-03

## 1. 功能概述

知微随安装包自带 26 个预置 Skill（源类型 `BUILTIN`），覆盖日常管理、开发辅助、数据分析、内容创作、自动化、集成等常见场景。这些 Skill 以 `SKILL.md` 形式打包在应用 classpath 中（`src/main/resources/skills/`），首次启动时由 `SkillDiscoveryRegistrar` 走统一 `SkillInstaller` 流水线安装到用户 Skill 目录（默认 `~/.zhiwei/skills/<name>/`），同时写入 `skills` 表 `source_type = BUILTIN`，并注册到内存索引以便 Agent 发现与激活。

前端"技能目录"中，BUILTIN 与 USER_IMPORTED / MARKETPLACE / AUTO_GENERATED 用徽章区分。用户可以直接在 UI 上切换启用状态（内部调用 `PUT /api/skills/{name}/enabled`）。

## 2. 核心特性

### 2.1 启动自动安装

- 启动时扫描 `classpath:skills/*/SKILL.md`
- 逐个走 `SkillInstaller`：parse → validate（description + body 硬约束）→ 写到用户目录 → upsert `skills` 表
- 任一 skill 解析或校验失败只记 WARN 跳过，不阻断其他 BUILTIN 安装，也不阻塞应用启动

### 2.2 按需激活

BUILTIN skill 不会全部注入 Agent 上下文，而是通过统一激活入口：

1. system prompt 包含所有启用且依赖满足的 skill 目录摘要（`<skill_catalog>` 内嵌扁平 markdown list，由 `ContextAssembler.buildSkillCatalog` 按当前任务关键词打分取 top-8 生成）
2. Agent 根据用户请求调用 `skill.load(names=["skill-a"])`（一次 1-3 个）
3. `SkillActivator` 读取 SKILL.md body，替换 `{skill_dir}` 等占位符
4. 返回结果由 Agent 引擎注入到下一轮上下文；Skill 提到的非核心工具仍通过 `tool.search` 发现

### 2.3 用户可编辑

BUILTIN skill 安装后其 SKILL.md 文件在用户目录中。用户可以直接编辑（或通过前端"技能编辑器"），保存后 `SkillFileWatcher` 500ms 防抖热加载。后台 `skills` 表的 checksum 也会同步更新。

## 3. 预置 Skill 清单

| name | 名称 | 说明 |
|---|---|---|
| `a2ui` | 界面生成 | 根据用户描述生成前端界面代码 |
| `api-debugger` | API 调试 | HTTP 请求调试与 API 接口测试 |
| `browser-automation` | 浏览器自动化 | 网页操作自动化（点击、填写、截图）|
| `code-assistant` | 代码助手 | 代码编写、审查与重构辅助 |
| `content-creator` | 内容创作 | 文章、文案、社交媒体内容生成 |
| `cron-scheduler` | 定时任务调度 | Cron 定时任务创建与管理 |
| `daily-manager` | 日常管理 | 日程安排、待办事项、日常规划 |
| `data-analyst` | 数据分析 | 数据处理、统计分析、可视化 |
| `database-query` | 数据库查询 | SQL 查询执行与结果分析 |
| `desktop-automation` | 桌面自动化 | 桌面应用操作自动化 |
| `doc-processor` | 文档处理 | 文档解析、转换与摘要 |
| `feishu` | 飞书集成 | 飞书消息发送、文档操作 |
| `file-organizer` | 文件整理 | 文件分类、重命名、目录整理 |
| `find-skills` | 能力发现与自扩展 | 搜索外部 Skill 或自动生成缺失 Skill |
| `gitee` | Gitee 集成 | Gitee 仓库操作与 Issue 管理 |
| `github-workflow` | GitHub 工作流 | GitHub 仓库、PR、Issue 操作 |
| `healthcheck` | 健康检查 | 系统资源检查（CPU/内存/磁盘）|
| `introspection` | 系统自省 | 查看运行时状态：Skill/工具/MCP 连接 |
| `log-analyzer` | 日志分析 | 日志搜索、错误诊断、模式识别 |
| `research-assistant` | 研究助手 | 信息检索、资料整理、调研报告 |
| `skill-creator` | Skill 创作指南 | v2 规范自举样本，用于手写或改写 SKILL.md |
| `summarizer` | 内容摘要 | 长文本摘要与关键信息提取 |
| `teaching-assistant` | 教学助手 | 知识讲解、练习生成、学习规划 |
| `web-novel-writer` | 网文写作 | 网络小说创作辅助 |
| `workflow-creator` | 工作流创建 | YAML 工作流定义与编排 |

## 4. 使用场景

**场景一：用户首次启动**

用户安装知微后首次启动，`SkillDiscoveryRegistrar` 自动把 26 个预置 Skill 安装到用户目录并入 `skills` 表。Agent 在首次对话中即可发现并激活这些 Skill。

**场景二：定制预置 Skill**

用户发现 `cron-scheduler` 的默认行为不满足需求，直接编辑 `~/.zhiwei/skills/cron-scheduler/SKILL.md`（或通过前端"技能编辑器"）添加自定义指令。保存后 `SkillFileWatcher` 热加载，下次对话即生效。

**场景三：临时禁用某个预置 skill**

用户在前端"技能目录"中对 `web-novel-writer` 点击启用开关切为"已停用"，对应写入 `skills.enabled = 0`；`ContextAssembler.buildSkillCatalog` 下次构建时会跳过该条目，`skill.load` 调用时校验 `enabled` 会拒绝激活。

## 5. 配置项

| 配置键 | 默认值 | 说明 |
|---|---|---|
| `lifepilot.meta.skill-discovery.enabled` | `true` | BUILTIN Skill 启动安装开关 |
| `lifepilot.skills.directory` | `${zhiwei.data-dir}/skills` | Skill 文件目录（安装目标）|
| `lifepilot.skills.skill-filename` | `SKILL.md` | Skill 定义文件名 |

## 6. 当前限制

- BUILTIN 与其他来源在前端用徽章区分，但在后端 `SkillDefinition` 内存模型里目前仍借用 `SkillSource.UserDefined` 承载 `folderPath`（源类型真值由 `skills` 表 `source_type` 列记录）。
- v1 格式（含老 `id:` 字段或缺少 v2 必需小节）的 SKILL.md 会被 parser 拒绝并跳过——升级中迁移预置 Skill 时需逐个核对 v2 规范。
- 预置 Skill 的指令内容为静态 Markdown，不支持动态参数注入（只有 `{skill_dir}` / `{skill_references_dir}` / `{skill_scripts_dir}` 三个占位符由激活时替换）。
