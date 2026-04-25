# 文件整理工作流详解

## 了解当前状态

```
file.list(action="list", path="目标目录", maxDepth=3)
file.list(action="info", path="目标文件")
```

## 分类依据矩阵

| 分类依据 | 适用场景 | 示例 |
|---------|---------|------|
| 文件类型 | 混杂文件整理 | `.pdf` → docs/、`.jpg` → images/ |
| 日期 | 照片/日志整理 | 按年月创建子目录 |
| 项目名 | 工作文件整理 | 按项目归档 |
| 文件大小 | 磁盘清理 | 大文件单独归类 |

## 预览变更

**在执行任何文件操作前，先输出变更预览供用户确认。**

## 执行操作（优先 file.manage，跨平台）

```
file.manage(action="move", source="old/path/file.pdf", destination="new/path/file.pdf")
file.manage(action="copy", source="src/file.txt", destination="backup/file.txt")
file.manage(action="mkdir", path="docs/2026")
file.manage(action="delete", path="temp/useless.tmp")
```

## 验证结果

```
file.list(action="list", path="目标目录", maxDepth=3)
```

## 常见错误处理

- **文件名冲突** → 自动添加序号后缀
- **权限不足** → 提示用户手动授权或使用管理员权限
- **路径过长** → 缩短目录层级或文件名
