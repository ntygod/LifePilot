---
name: file-organizer
description: 当用户要整理文件、批量重命名、按类型/日期/项目分类归档、清理重复文件或分析磁盘空间占用时使用。关键词：整理文件、文件分类、批量重命名、清理文件、文件归档、磁盘空间、文件管理、按日期归档、按项目归档。单个文件读写直接用 file.read / file.write，代码文件重构用 code-assistant，文档格式转换用 doc-processor。
version: 2.0.0
metadata:
  zhiwei:
    priority: normal
    tags:
      - file
      - organize
      - rename
      - archive
      - disk-cleanup
    suggested_tools:
      - file.list
      - file.read
      - file.write
      - file.manage
      - shell.exec
---

# 文件管理指南

帮助用户整理文件、优化目录结构和批量处理文件操作。

## 适用场景

- 文件批量重命名（按规则、日期、序号）
- 目录结构整理和优化
- 文件分类归档（按类型、日期、项目）
- 重复文件检测和清理
- 磁盘空间分析

## 不适用场景

- 单个文件读写 → 直接用 `file.read` / `file.write`
- 代码文件重构 → 用 code-assistant
- 文档格式转换 → 用 doc-processor

## 工作流

1. **了解现状**：`file.list` 看目录结构和文件信息
2. **制定方案**：选分类依据（类型 / 日期 / 项目 / 大小）
3. **预览变更**：执行前必须输出预览，用户确认后再操作
4. **执行**：优先 `file.manage`（跨平台），避免 `shell.exec` 的 mv/rm
5. **移动优先于删除**：先归档到临时目录，确认无误再清理
6. **批量分批**：大批量操作分批执行，每批确认后再继续
7. **重命名记映射**：用 `file.write` 记录旧名 → 新名，便于回溯

## 详细参考

- 分类依据矩阵、命令模板、错误处理：`{skill_dir}/references/organize-patterns.md`
</content>
</invoke>