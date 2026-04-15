# 预置 Skill — 特性说明

> **文档性质**：特性说明文档
> **模块归属**：`com.lifepilot.meta.convenience` / `com.lifepilot.skill`
> **最后更新**：2026-04

## 1. 功能概述

知微内置 25 个预置 Skill（种子 Skill），覆盖日常管理、开发辅助、数据分析、内容创作、自动化等常见场景。这些 Skill 以 `SKILL.md` 形式打包在应用 classpath 中（`src/main/resources/skills/`），首次启动时由 `SkillDiscoveryRegistrar` 自动提取到用户 Skill 目录（`~/.zhiwei/skills/`）。

提取后，预置 Skill 与用户自定义 Skill 完全等同——来源类型为 `UserDefined`，支持热加载、编辑覆盖和按需激活。

## 2. 核心特性

### 2.1 首次提取、不覆盖

- 启动时扫描 classpath 下 `skills/*/SKILL.md`
- 用户目录中已存在同名文件夹时跳过，不覆盖用户自定义内容
- 提取结果日志记录新增数量和总扫描数量

### 2.2 按需激活

预置 Skill 不会全部加载到 Agent 上下文，而是通过渐进式发现机制按需激活：

1. 系统提示词包含所有已注册 Skill 的目录摘要（XML 格式）
2. Agent 根据用户请求从目录中选择匹配的 Skill
3. 调用 `file.read(skill="skill-id")` 加载 Skill 指南并激活建议工具

### 2.3 用户可编辑

提取到用户目录后，用户可以直接编辑 `SKILL.md` 定制 Skill 行为。编辑后系统自动热加载，下次对话即生效。由于提取逻辑"已存在则跳过"，用户修改不会被后续启动覆盖。

## 3. 预置 Skill 清单

| Skill ID | 名称 | 说明 |
|----------|------|------|
| `a2ui` | 界面生成 | 根据用户描述生成前端界面代码 |
| `api-debugger` | API 调试 | HTTP 请求调试与 API 接口测试 |
| `browser-automation` | 浏览器自动化 | 网页操作自动化（点击、填写、截图） |
| `code-assistant` | 代码助手 | 代码编写、审查与重构辅助 |
| `content-creator` | 内容创作 | 文章、文案、社交媒体内容生成 |
| `cron-scheduler` | 定时任务调度 | Cron 定时任务创建与管理 |
| `daily-manager` | 日常管理 | 日程安排、待办事项、日常规划 |
| `data-analyst` | 数据分析 | 数据处理、统计分析、可视化 |
| `database-query` | 数据库查询 | SQL 查询执行与结果分析 |
| `datastore` | 数据存储 | 通用数据存取（Datastore CRUD） |
| `desktop-automation` | 桌面自动化 | 桌面应用操作自动化 |
| `doc-processor` | 文档处理 | 文档解析、转换与摘要 |
| `feishu` | 飞书集成 | 飞书消息发送、文档操作 |
| `file-organizer` | 文件整理 | 文件分类、重命名、目录整理 |
| `find-skills` | 能力发现与自扩展 | 搜索外部 Skill 或自动生成缺失 Skill |
| `gitee` | Gitee 集成 | Gitee 仓库操作与 Issue 管理 |
| `github-workflow` | GitHub 工作流 | GitHub 仓库、PR、Issue 操作 |
| `healthcheck` | 健康检查 | 系统资源检查（CPU/内存/磁盘） |
| `introspection` | 系统自省 | 查看运行时状态：Skill/工具/MCP 连接 |
| `log-analyzer` | 日志分析 | 日志搜索、错误诊断、模式识别 |
| `research-assistant` | 研究助手 | 信息检索、资料整理、调研报告 |
| `summarizer` | 内容摘要 | 长文本摘要与关键信息提取 |
| `teaching-assistant` | 教学助手 | 知识讲解、练习生成、学习规划 |
| `web-novel-writer` | 网文写作 | 网络小说创作辅助 |
| `workflow-creator` | 工作流创建 | YAML 工作流定义与编排 |

## 4. 使用场景

### 4.1 用户首次启动

用户安装知微后首次启动，`SkillDiscoveryRegistrar` 自动将 25 个预置 Skill 提取到 `~/.zhiwei/skills/`。Agent 在首次对话中即可发现和使用这些 Skill。

### 4.2 定制预置 Skill

用户发现 `cron-scheduler` 的默认行为不满足需求，直接编辑 `~/.zhiwei/skills/cron-scheduler/SKILL.md` 添加自定义指令。保存后系统热加载，后续对话自动使用定制版本。

### 4.3 预置 Skill 与自定义 Skill 共存

用户在 `~/.zhiwei/skills/` 下创建自定义 `finance-analyst/SKILL.md`，与预置 Skill 并存于同一目录，注册和激活流程完全一致。

## 5. 配置项

| 配置键 | 默认值 | 说明 |
|--------|--------|------|
| `lifepilot.meta.skill-discovery.enabled` | `true` | 种子 Skill 提取开关 |
| `lifepilot.skills.directory` | `~/.zhiwei/skills` | Skill 文件目录（提取目标） |
| `lifepilot.skills.skill-filename` | `SKILL.md` | Skill 定义文件名 |

## 6. 当前限制

- 预置 Skill 提取后与用户自定义 Skill 无区分标识，无法单独查询"哪些是预置的"
- 提取逻辑为"已存在则跳过"，应用升级后新版本的预置 Skill 内容不会自动同步到已存在的用户目录
- 预置 Skill 的指令内容为静态 Markdown，不支持动态参数注入（`{skill_scripts_dir}` 占位符除外）
