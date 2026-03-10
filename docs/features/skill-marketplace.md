# Skill 市场功能说明

> **文档性质**：特性说明文档
> **模块归属**：`com.lifepilot.marketplace`
> **最后更新**：2026-03

## 1. 功能概述

Skill 市场为 ZhiWei 用户提供一站式的 Skill 发现、安装和管理体验。用户可以从社区索引中浏览可用 Skill，一键安装到本地，并通过版本管理保持 Skill 更新。

### 用户价值

- **降低使用门槛**：无需手动编写 YAML，直接安装社区贡献的 Skill
- **扩展 Agent 能力**：通过安装专业 Skill 快速获得新能力（如财务分析、健康管理、学习规划等）
- **安全保障**：安装前自动安全扫描，识别潜在风险
- **版本管理**：自动检测可用更新，一键升级

## 2. 核心特性

### 2.1 Skill 浏览与搜索

- 从配置的索引源获取可用 Skill 列表
- 支持关键词搜索（按名称、描述模糊匹配）
- 支持标签筛选（productivity、health、finance 等）
- 展示 Skill 详情：名称、描述、作者、版本、标签、下载量、是否已验证

### 2.2 Skill 安装

- 一键安装：从远程仓库下载 YAML 文件到本地 skills 目录
- 安装前安全扫描：检测危险工具使用、Prompt 注入模式
- 安全报告展示：风险级别（LOW/MEDIUM/HIGH）+ 详细说明
- 高风险 Skill 需用户明确确认后才能安装
- 安装后自动注册到 SkillRegistry，立即可用

### 2.3 Skill 卸载

- 一键卸载：从 SkillRegistry 注销 + 删除本地 YAML 文件 + 清理安装记录
- 卸载确认对话框，防止误操作

### 2.4 版本管理

- 基于 SemVer 的版本比较
- 自动检测可用更新（对比本地已安装版本与索引中最新版本）
- 一键升级：下载新版本 YAML，替换本地文件，重新加载
- 版本兼容性检查：minLifepilotVersion 约束

### 2.5 索引管理

- 支持配置多个索引源（官方、社区、私有）
- 手动刷新索引缓存
- 索引缓存持久化到 SQLite，离线时使用缓存

### 2.6 安全扫描

- 危险工具检测：shell_execute、http_request 等高风险工具
- Prompt 注入模式检测：识别 System Prompt 中的注入尝试
- 未知工具 ID 检测：工具白名单中引用了本地不存在的工具
- 版本格式校验：确保版本号符合 SemVer 规范

## 3. 使用场景

### 场景 1：浏览并安装社区 Skill

用户打开 Web UI 的 Skill 市场页面，浏览可用 Skill 列表。看到一个"周计划助手"Skill，点击查看详情，确认安全报告无高风险项后点击安装。安装完成后，在对话中即可使用该 Skill。

### 场景 2：检测并升级 Skill

用户在 Skill 管理页面看到"有 2 个 Skill 可更新"的提示。点击查看更新列表，确认变更内容后一键升级。

### 场景 3：配置私有索引源

企业用户在 application.yml 中添加私有 GitHub 仓库作为索引源，团队成员可以安装内部共享的 Skill。

## 4. 配置项

| 配置键 | 默认值 | 说明 |
|--------|--------|------|
| `lifepilot.marketplace.enabled` | `true` | 市场功能总开关 |
| `lifepilot.marketplace.index-sources` | 官方索引 URL | 索引源 URL 列表 |
| `lifepilot.marketplace.cache-ttl-hours` | `24` | 索引缓存有效期（小时） |
| `lifepilot.marketplace.auto-check-updates` | `true` | 是否自动检测更新 |
| `lifepilot.marketplace.security.block-high-risk` | `false` | 是否自动阻止高风险 Skill 安装 |
| `lifepilot.marketplace.security.injection-patterns` | 内置模式列表 | Prompt 注入检测正则模式 |

## 5. REST API

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/marketplace/skills` | 获取可用 Skill 列表（支持 search、tag 查询参数） |
| GET | `/api/marketplace/skills/{id}` | 获取 Skill 详情 |
| POST | `/api/marketplace/skills/{id}/install` | 安装 Skill |
| DELETE | `/api/marketplace/skills/{id}` | 卸载 Skill |
| POST | `/api/marketplace/index/refresh` | 手动刷新索引缓存 |
| GET | `/api/marketplace/updates` | 检测可用更新 |
| POST | `/api/marketplace/skills/{id}/upgrade` | 升级 Skill 到最新版本 |

## 6. 限制与未来扩展

### 当前限制

- 仅支持 YAML 声明式 Skill，不支持 Java 原生 Skill 的远程安装
- 不支持 Skill 间依赖关系
- 安全扫描为本地规则匹配，无 LLM 辅助的深度分析
- 不支持 Skill 评分和评论

### 未来扩展方向

- MCP Server 市场：扩展为通用的 MCP Server 发现和安装
- LLM 辅助安全审核：使用 LLM 分析 Skill 的 System Prompt 安全性
- 社区评分系统：用户对已安装 Skill 进行评分和评论
- Skill 发布工具：提供命令行工具简化 Skill 发布到索引仓库的流程
