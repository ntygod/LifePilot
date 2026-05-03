---
name: file-organizer
description: 当用户要整理文件、批量重命名、按类型/日期/项目分类归档、清理重复文件或分析磁盘空间占用时使用。
version: 2.1.0
metadata:
  zhiwei:
    tags:
      - file
      - organize
      - rename
      - archive
      - disk-cleanup
      - dedup
    suggested_tools:
      - file_read
      - file_write
      - file_manage
      - shell_exec
---

# 文件管理指南

批量整理 / 重命名 / 归档 / 去重 / 磁盘分析。**核心约束：所有破坏性操作必须先输出预览，用户确认才执行。**

## 适用场景

- 批量重命名（按规则 / 日期 / 序号）
- 目录结构整理（按类型 / 日期 / 项目分类归档）
- 重复文件检测与清理
- 磁盘空间分析（哪些目录大）
- 旧文件清理（按修改时间）

## 不适用场景

- 单个文件读 / 写 → `file_read` / `file_write`
- 代码文件重构 → code-assistant
- 文档格式转换（docx ↔ pdf 等）→ doc-processor

## 工作流（按用户表达分流）

| 用户表达 | 路径 |
|---|---|
| "整理一下 X 目录" | 现状 → 方案 → 预览 → 执行 |
| "把所有 X 改成 Y 命名" | 列匹配文件 → 预览映射 → 批量重命名 |
| "看看哪些文件重复" | 扫 + 算 hash → 列重复组 → 用户决定保留哪个 |
| "磁盘满了" | 按大小排序目录 → 给用户高占用清单 |
| "清理 30 天前没改过的" | 按 mtime 筛 → 预览 → 移到回收目录 |

各路径要点：

- **预览不可省**：执行前列出"会改哪些 / 改成什么"给用户对，不直接动手
- **优先 file_manage**：跨平台，避免 `shell_exec` 的 mv / rm 差异（Windows 没有 mv）
- **移动优于删除**：删除 / 覆盖先归档到操作目录下 `.trash/<日期>/` 子目录，用户确认无误后再清
- **大批量分批**：> 100 个文件分批跑，每批后让用户回看
- **重命名记映射**：批量重命名后落 `rename-map.json`（旧名→新名）到操作目录，便于回溯

## 详细参考

- 分类依据矩阵、命令模板、错误处理：`{skill_dir}/references/organize-patterns.md`
