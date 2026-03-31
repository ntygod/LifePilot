# 扩展市场功能说明

> **文档性质**：特性说明文档
> **模块归属**：`com.lifepilot.marketplace`
> **最后更新**：2026-03

## 1. 功能概述

扩展市场（Extension Marketplace）为 ZhiWei 用户提供一站式的扩展发现、安装和管理体验。用户可以从社区索引中浏览可用扩展（Skill、Agent、Workflow、Channel），一键安装到本地，并通过版本管理保持更新。

### 用户价值

- **降低使用门槛**：无需手动编写 SKILL.md，直接安装社区贡献的扩展
- **扩展 Agent 能力**：通过安装专业扩展快速获得新能力（如财务分析、健康管理、学习规划等）
- **安全保障**：安装前自动安全扫描，识别潜在风险
- **版本管理**：自动检测可用更新，一键升级

## 2. 核心特性

### 2.1 扩展浏览与搜索

- 从配置的索引源获取可用扩展列表
- 支持关键词搜索（按名称、描述模糊匹配）
- 支持标签筛选（productivity、health、finance 等）
- 支持按扩展类型筛选（SKILL、AGENT、WORKFLOW、CHANNEL）
- 展示扩展详情：名称、描述、作者、版本、标签、下载量、是否已验证

### 2.2 扩展安装

- 一键安装：从远程仓库下载 SKILL.md 文件夹到本地 skills 目录
- 安装前安全扫描：`SecurityScanner` 检测危险工具使用、Prompt 注入模式
- 安全报告展示：`SecurityReport` 包含风险级别（LOW/MEDIUM/HIGH）+ 详细说明
- 高风险扩展需用户明确确认后才能安装
- 安装后自动注册到 SkillRegistry，立即可用

### 2.3 扩展卸载

- 一键卸载：从 SkillRegistry 注销 + 删除本地 SKILL.md 文件夹 + 清理 `InstalledExtension` 安装记录
- 卸载确认对话框，防止误操作

### 2.4 版本管理

- 基于 SemVer 的版本比较
- 自动检测可用更新（对比本地已安装版本与索引中最新版本）
- 一键升级：下载新版本 SKILL.md，替换本地文件，重新加载
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

### 场景 1：浏览并安装社区扩展

用户打开 Web UI 的扩展市场页面，浏览可用扩展列表。看到一个"周计划助手"Skill，点击查看详情，确认安全报告无高风险项后点击安装。安装完成后，在对话中即可使用该 Skill。

### 场景 2：检测并升级扩展

用户在扩展管理页面看到"有 2 个扩展可更新"的提示。点击查看更新列表，确认变更内容后一键升级。

### 场景 3：配置私有索引源

企业用户在 application.yml 中添加私有 GitHub 仓库作为索引源，团队成员可以安装内部共享的 Skill。

## 4. 配置项

| 配置键 | 默认值 | 说明 |
|--------|--------|------|
| `lifepilot.marketplace.enabled` | `true` | 市场功能总开关 |
| `lifepilot.marketplace.index-sources` | 官方索引 URL | 索引源 URL 列表 |
| `lifepilot.marketplace.cache-ttl-hours` | `24` | 索引缓存有效期（小时） |
| `lifepilot.marketplace.auto-check-updates` | `true` | 是否自动检测更新 |
| `lifepilot.marketplace.security.block-high-risk` | `false` | 是否自动阻止高风险扩展安装 |
| `lifepilot.marketplace.security.injection-patterns` | 内置模式列表 | Prompt 注入检测正则模式 |

## 5. REST API

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/marketplace/extensions` | 获取可用扩展列表（支持 search、tag、type 查询参数） |
| GET | `/api/marketplace/extensions/{id}` | 获取扩展详情 |
| POST | `/api/marketplace/extensions/{id}/install` | 安装扩展 |
| DELETE | `/api/marketplace/extensions/{id}` | 卸载扩展 |
| POST | `/api/marketplace/index/refresh` | 手动刷新索引缓存 |
| GET | `/api/marketplace/updates` | 检测可用更新 |
| POST | `/api/marketplace/extensions/{id}/upgrade` | 升级扩展到最新版本 |

## 6. 限制与未来扩展

### 当前限制

- 仅支持 Markdown 声明式 Skill（SKILL.md），不支持 Java 原生 Skill 的远程安装
- 不支持扩展间依赖关系
- 安全扫描为本地规则匹配，无 LLM 辅助的深度分析
- 不支持扩展评分和评论

### 未来扩展方向

- MCP Server 市场：扩展为通用的 MCP Server 发现和安装
- LLM 辅助安全审核：使用 LLM 分析 Skill 的 System Prompt 安全性
- 社区评分系统：用户对已安装扩展进行评分和评论
- 扩展发布工具：提供命令行工具简化扩展发布到索引仓库的流程
