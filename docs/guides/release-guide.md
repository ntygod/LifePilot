# Release 发布指南

> 适合个人维护者的最小发布流程，目标是稳定、可回溯、低负担。

## 1. 什么时候发版

满足下面任一条件时，可以考虑发一个版本：

- 有一组完整可用的新能力
- 修复了值得对外通知的问题
- 文档、配置、行为已经和当前代码一致
- 你希望给别人一个可引用的稳定标签

如果只是日常小修，继续走 `develop` 即可，不需要为了发版而发版。

## 2. 版本号建议

当前项目更适合使用 `v0.x.y`：

- `v0.x.0`：一组可感知的新能力或结构性变化
- `v0.x.y`：兼容性的修复、文档更新、体验改进

示例：

- `v0.2.0`
- `v0.2.1`

## 3. 发版前检查

发版前至少确认：

- `develop` 分支是绿的
- PR 都已经合并，没有待处理的高优先级缺陷
- `README.md`、`docs/ARCHITECTURE.md`、`docs/FEATURES.md` 已同步
- 如涉及接口、配置或迁移，相关文档已经写明

建议执行：

```bash
mvn test
cd zhiwei-web
npm run test:run
npm run build
```

## 4. 推荐发版方式

### 方式一：GitHub 网页

1. 打开仓库的 `Releases`
2. 点击 `Draft a new release`
3. 创建标签，例如 `v0.2.0`
4. 勾选自动生成 Release Notes
5. 检查分类是否合理
6. 补充升级说明、破坏性变更和注意事项
7. 先保存草稿，确认无误后再发布

仓库已提供 `.github/release.yml`，GitHub 会按 label 自动整理发布说明。

### 方式二：GitHub CLI

先创建草稿：

```bash
gh release create v0.2.0 --draft --generate-notes --target develop --title "v0.2.0"
```

确认草稿内容后，再到网页上补充说明并发布。

## 5. Release Notes 建议结构

每次发版正文尽量保持这 4 段：

1. 这版主要带来了什么
2. 需要注意的行为变化
3. 升级步骤或迁移要求
4. 已知限制或后续计划

如果没有破坏性变更，也建议明确写一句“本版本无破坏性变更”。

## 6. 发版后要做什么

- 检查 Release 页是否显示正确
- 确认 tag 指向预期提交
- 如有安装说明变化，同步更新 README
- 如这是一个重要版本，可新增一个置顶 Issue 或 Discussion 介绍变化

## 7. 当前仓库建议

对 ZhiWei 来说，当前最合适的习惯是：

- 功能开发持续合并到 `develop`
- 用 `Squash merge` 保持历史整洁
- 版本发布时从 `develop` 打 tag
- 每个版本都保留一份可读的 Release Notes
