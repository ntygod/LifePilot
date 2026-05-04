# 文档工作空间 — 特性说明

> **文档性质**：特性说明文档
> **模块归属**：`com.lifepilot.document`
> **最后更新**：2026-05-04
> **实现状态**：保留解析与历史工作区基础设施；Java 生成 / 编辑工具已下架

## 1. 当前定位

文档处理主路径已经迁移到 `doc-processor` Skill：生成 Word/Excel/PPT、PDF 提取、格式转换、批量处理都应通过 `code`、`shell.exec`、Python 库或外部 CLI 完成。

`com.lifepilot.document` 目前只保留两类仍有运行时价值的基础设施：

- `DocumentParserService`：供 `file.read` 读取 `md/txt/docx/pdf/xlsx/pptx/csv` 等结构化文档时路由到知识库 parser。
- 文档工作区 REST/版本链：供已有 `session_documents` 工作副本在前端下载、查看版本、diff、commit、rollback、discard。

旧的 `document.create` / `document.edit` BuiltinTool 已下架，不再作为 Agent 文档处理入口。

## 2. 支持边界

| 能力 | 当前入口 | 状态 |
|---|---|---|
| 读取 docx/pdf/xlsx/pptx 内容 | `file.read` → `DocumentParserService` | 保留 |
| 生成 Word/Excel/PPT | `doc-processor` Skill + `code` / `shell.exec` | 主路径 |
| Markdown/HTML/DOCX/PDF 转换 | `doc-processor` Skill + pandoc/脚本 | 主路径 |
| 现有工作副本下载 | `/api/documents/{id}/download` | 保留 |
| 现有工作副本版本/diff | `/api/documents/{id}/versions`、`/diff` | 保留 |
| 用户确认后覆盖/另存/回滚/丢弃 | `/commit`、`/rollback`、`/working-copy` | 保留 |
| Java POI 生成器 | 无运行时入口 | 已删除 |
| `document.create` / `document.edit` 工具 | 无运行时入口 | 已下架 |

## 3. Agent 行为原则

- 需要文档生成、转换、抽取、批量处理时，先加载 `doc-processor` Skill。
- 若缺少 `code`、`shell.exec`、`file.write` 等 schema，先通过 `tool.search` 发现工具。
- 不要让 LLM 调用 `document.create` / `document.edit`；这两个工具当前不存在。
- 不要为了生成文档恢复 Java POI 工具链；优先复用脚本、Python 库和成熟 CLI。

## 4. 配置项

| 配置键 | 默认值 | 说明 |
|---|---|---|
| `lifepilot.document.enabled` | `true` | 文档工作区基础设施开关 |
| `lifepilot.document.storage-dir` | `${user.home}/.zhiwei/documents` | 历史工作副本根目录 |
| `lifepilot.document.default-max-chars` | `30000` | 结构化解析默认最大字符数 |
| `lifepilot.gateway.channels.web.enabled` | `true` | 控制 `/api/documents` REST 端点是否装载 |

## 5. 后续清理方向

如果确认前端不再需要历史工作副本和 DiffCard，可以继续删除 `DocumentController`、版本链仓库、patch 引擎、前端文档工作区组件以及 `session_documents/document_versions` 相关迁移。
