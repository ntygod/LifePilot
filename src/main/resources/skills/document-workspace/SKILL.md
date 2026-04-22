---
id: document-workspace
name: "文档工作副本编辑"
description: "修改已存在的 docx / xlsx / pptx 文档，保留版本链和回滚能力。用户说「在 XXX.docx 后加一段」「把第 3 行改成 X」「修改一下 YY.xlsx」「给表格加一行」「回滚到上一版」「保存这份改动」时使用。不适用于从零创建全新文档（用 document.create）。"
version: "1.0.0"
---

<!--
  document.edit / document.create 已在 application.yml 的 core-tool-ids 白名单里（默认 LLM 可见），
  此处不再通过 suggested-tools 重复激活，避免双重机制；若将来从 core-tool-ids 摘掉，
  再在这里补 suggested-tools 字段即可。
-->


# 文档工作副本编辑指南

对已存在的 docx / xlsx / pptx 通过锚点定位施加编辑，每次编辑生成新版本工作副本，落盘/回滚/丢弃由用户自行决定。

## 适用场景

- 修改本会话内 `document.create` 刚生成的文档
- 修改用户以附件形式贴过来的文档
- 修改用户给出本地路径的文档
- 回滚到历史版本 / 丢弃工作副本

## 不适用场景

- 从零创建全新文档 → 用 `document.create`
- 文档格式转换（如 docx→pdf）→ 本能力不支持

## 工作流

### 1. 识别 source

根据用户语境选 `source.type`：

| 用户场景 | source 写法 |
|---|---|
| 刚在本会话用 `document.create` 生成的 | `{type:"document", id:"<上一轮返回的 documentId>"}` |
| 用户贴了附件（消息里有 attachment） | `{type:"attachment", id:"<附件 id>"}` |
| 用户给了本地绝对路径 | `{type:"path", value:"<绝对路径>"}` |

### 2. 规划 anchor

docx 用 `before_context + target + after_context` 组合锚点（各带 10-30 字保证整文唯一）；xlsx 用精确 sheet 名 + A1 地址。

docx / xlsx / pptx 是 ZIP + XML 二进制，**不要用 `file.read` 读正文**（读出来是乱码）。锚点信息源：
- 刚由 `document.create` 生成：用自己写入的 markdown 原文做 target
- 其他来源：直接尝试 patch，失败时 `document.edit` 会返回 `documentOutline` 段落预览供你调整

### 3. 施加 patch

```
document.edit(action="patch", source={...}, operations=[...])
```

单次 patch 可传多个 operations（事务性，整批成败一致）。

## 约束

- **永远不要用 `document.create` 重新生成已存在的文档**——会产生孤立 documentId，丢失版本链和回滚能力
- docx op 和 xlsx op 不能同批混用
- `commit` 是**用户动作**，代码层硬控制：缺 `userConfirmation` 或值不等于 `commitTarget` 会直接拒绝。调用前必须先 reply 用户说明 target 与路径，收到"确认/是的/可以"等明确同意后再带上 `userConfirmation`（值精确等于 `commitTarget`，例如 `"overwrite"` 或 `"saveAs"`）。
- `rollback` / `discard` 也是用户动作，LLM 不主动发起
- patch 同一文档累计失败 ≥ 2 次就停下问用户，不要盲试；runtime 连续 3 次硬熔断
- 不要用 `file.read` 读 docx/xlsx/pptx 正文（二进制格式）

## 反模式

- ❌ patch 成功后立刻链式调用 commit —— commit 必须等用户明确同意，擅自带 `userConfirmation` 属于绕过安全校验
- ❌ 发现上一轮没保留 documentId 就退而求其次用 `document.create` 重建——应引导用户重新提供源文件路径或附件，不要自行重建（会丢失版本链）
- ❌ 把 `document.create` 当万能入口来"覆盖保存"——覆盖靠 `document.edit action=commit`，不是重新 create 同名文件
- ❌ 一个 patch 里混合 docx 和 xlsx 的 op

## 常见错误处理

- **patch 失败** `failedOps` 附 `documentOutline` 段落预览 → 照真实文本重写 locator 的 context 重试
- **用户说"改一下文档"但没指明哪份** → 列本会话已有文档（documentId + fileName）让用户选
- **上下文里找不到 documentId / attachment id / 路径** → 直接问用户"要改哪一份？"
