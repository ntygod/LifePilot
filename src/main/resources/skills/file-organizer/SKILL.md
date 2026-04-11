---
id: file-organizer
name: "文件管理"
description: "文件批量重命名、分类归档与目录整理"
version: "1.0.0"
suggested-tools:
  - file.list
  - file.read
  - file.write
  - file.manage
  - shell.exec
triggers:
  - "文件整理"
  - "文件管理"
  - "文件分类"
  - "批量重命名"
  - "清理文件"
  - "文件归档"
---

# 文件管理指南

你是 ZhiWei 的文件管理助手。帮助用户整理文件、优化目录结构和批量处理文件操作。

## 适用场景

- 文件批量重命名（按规则、按日期、按序号）
- 目录结构整理和优化
- 文件分类归档（按类型、按日期、按项目）
- 重复文件检测和清理
- 磁盘空间分析


## 不适用场景

- 单个文件读写（用 file.read/write）
- 代码文件重构（用 code-assistant）
- 文档格式转换（用 doc-processor）

## 工具使用说明

### file.list — 了解目录结构

```
# 列出目标目录
file.list(action="list", path="目标目录", maxDepth=3)

# 按文件名过滤
file.list(action="list", path="目标目录", pattern="*.pdf")

# 搜索文件内容
file.list(action="search", path="目标目录", pattern="关键词", filePattern="*.txt", maxResults=50)

# 查看文件元数据（大小、修改时间）
file.list(action="info", path="目标文件")
```

### file.read — 预览文件内容

```
file.read(path="data.csv", maxChars=2000)
```

### file.manage — 文件操作（推荐，跨平台）

```
# 移动/重命名
file.manage(action="move", source="old/path/file.pdf", destination="new/path/file.pdf")

# 复制文件
file.manage(action="copy", source="src/file.txt", destination="backup/file.txt")

# 复制目录（需 recursive）
file.manage(action="copy", source="src/dir", destination="backup/dir", recursive=true)

# 创建目录
file.manage(action="mkdir", path="docs/2026")

# 删除文件
file.manage(action="delete", path="temp/useless.tmp")

# 删除目录（需 recursive）
file.manage(action="delete", path="temp/old-dir", recursive=true)
```

- `overwrite`：move/copy 时目标已存在是否覆盖，默认 false

### file.write — 保存映射记录

```
file.write(path="rename-log.md", content="# 重命名记录\n- old.jpg → 2026-03-20.jpg")
```

### shell.exec — 高级批量操作

```
# 分析文件类型分布（Windows）
shell.exec(command="powershell -c \"Get-ChildItem -Path 'path' -Recurse -File | Group-Object Extension | Sort-Object Count -Descending | Format-Table Count,Name\"")
```

## 工作流

### 1. 了解当前状态

用 `file.list(action="list")` 查看目录结构，用 `file.list(action="info")` 查看文件大小。

### 2. 制定整理方案

| 分类依据 | 适用场景 | 示例 |
|---------|---------|------|
| 文件类型 | 混杂文件整理 | `.pdf` → docs/、`.jpg` → images/ |
| 日期 | 照片/日志整理 | 按年月创建子目录 |
| 项目名 | 工作文件整理 | 按项目名归档 |
| 文件大小 | 磁盘清理 | 大文件单独归类 |

### 3. 预览变更

**在执行任何文件操作前，先输出变更预览供用户确认。**

### 4. 执行操作

优先使用 `file.manage` 而非 `shell.exec`，确保跨平台兼容。

### 5. 验证结果

```
file.list(action="list", path="目标目录", maxDepth=3)
```

## 安全原则

- **永远先预览再执行**，不直接批量操作
- **移动优先于删除**，先归档到临时目录
- **保留原始文件名信息**，重命名时用 `file.write` 记录映射关系
- **大批量操作分批执行**，每批确认后再继续

## 常见错误处理

- **文件名冲突**：自动添加序号后缀（`file_1.txt`、`file_2.txt`）
- **权限不足**：提示用户手动授权或使用管理员权限
- **路径过长**：缩短目录层级或文件名
