---
name: document-workspace
description: 当用户要修改已存在的 docx / xlsx / pptx 文档（基于锚点的定位编辑、生成新版本工作副本、支持回滚和丢弃）时使用。关键词：在 XXX.docx 后加一段、把第 3 行改成、修改 YY.xlsx、给表格加一行、回滚上一版、保存改动、文档工作副本。从零创建全新文档用 document.create，文档格式转换本 Skill 不支持。
version: 1.0.0
metadata:
  zhiwei:
    category: infrastructure
    priority: normal
    tags:
      - document
      - docx
      - xlsx
      - pptx
      - edit
      - version
      - workspace
---

# 文档工作副本编辑指南

对已存在的 docx / xlsx / pptx 通过锚点定位施加编辑，每次编辑生成新版本工作副本，落盘/回滚/丢弃由用户自行决定。

<!--
  document.edit / document.create 已在 application.yml 的 core-tool-ids 白名单里（默认 LLM 可见），
  此处不再通过 suggested_tools 重复激活，避免双重机制；若将来从 core-tool-ids 摘掉，
  再在这里补 suggested_tools 字段即可。
-->

## 适用场景

- 修改本会话内 `document.create` 刚生成的文档
- 修改用户以附件形式贴过来的文档
- 修改用户给出本地路径的文档
- 回滚到历史版本 / 丢弃工作副本

## 不适用场景

- 从零创建全新文档 → 用 `document.create`
- 文档格式转换（如 docx→pdf）→ 本能力不支持

## 工作流

1. **识别 source**：`document` / `attachment` / `path`，源类型写法见参考
2. **规划 anchor**：docx 用 before/target/after context（各 10-30 字保证唯一），xlsx 用 sheet + A1 地址
3. **不要 file.read 正文**：docx/xlsx/pptx 是 ZIP+XML 二进制，读出来是乱码
4. **施加 patch**：`document.edit action=patch`，单次可传多个 operations（事务性）
5. **commit 必须用户触发**：代码层硬控制，缺 `userConfirmation` 或值不等于 `commitTarget` 会直接拒绝；rollback / discard 同理
6. **失败硬熔断**：同一文档 patch 连续失败 ≥ 2 次停下问用户，runtime 连续 3 次强熔断
7. **不混用 op**：一个 patch 里不同时放 docx 和 xlsx 的 operations
8. **绝不用 create 重建已存在文档**——会丢 documentId、版本链和回滚能力

## 详细参考

- source 类型 / anchor 规划 / patch 细节 / commit 流程 / 反模式 / 错误处理：`{skill_dir}/references/edit-workflow.md`
</content>
</invoke>