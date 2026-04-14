---
id: file-organizer
name: "文件管理"
description: "文件批量重命名、分类归档与目录整理。用户说「整理文件」「文件分类」「批量重命名」「清理文件」「文件归档」「磁盘空间」「文件管理」时使用。不适用于单个文件读写（直接用 file.read/write）或文档格式转换（用 doc-processor）。"
version: "2.0.0"
suggested-tools:
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

### 1. 了解当前状态

```
file.list(action="list", path="目标目录", maxDepth=3)
file.list(action="info", path="目标文件")
```

### 2. 制定整理方案

| 分类依据 | 适用场景 | 示例 |
|---------|---------|------|
| 文件类型 | 混杂文件整理 | `.pdf` → docs/、`.jpg` → images/ |
| 日期 | 照片/日志整理 | 按年月创建子目录 |
| 项目名 | 工作文件整理 | 按项目归档 |
| 文件大小 | 磁盘清理 | 大文件单独归类 |

### 3. 预览变更

**在执行任何文件操作前，先输出变更预览供用户确认。**

### 4. 执行操作

优先使用 `file.manage`（跨平台）：

```
file.manage(action="move", source="old/path/file.pdf", destination="new/path/file.pdf")
file.manage(action="copy", source="src/file.txt", destination="backup/file.txt")
file.manage(action="mkdir", path="docs/2026")
file.manage(action="delete", path="temp/useless.tmp")
```

### 5. 验证结果

```
file.list(action="list", path="目标目录", maxDepth=3)
```

## 规则

- 执行前必须输出变更预览，用户确认后再操作
- 移动优先于删除——先归档到临时目录，确认无误再清理
- 重命名时用 `file.write` 记录映射关系（旧名 → 新名），便于回溯
- 大批量操作分批执行，每批确认后再继续
- 优先用 `file.manage` 而非 `shell.exec`，确保跨平台兼容

## 常见错误处理

- **文件名冲突** → 自动添加序号后缀
- **权限不足** → 提示用户手动授权或使用管理员权限
- **路径过长** → 缩短目录层级或文件名
