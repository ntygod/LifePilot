# GitHub 仓库维护指南

> 适合第一次维护个人开源项目时作为检查清单使用。

## 1. 基础信息

先在仓库首页补齐这些信息：

- 仓库描述：一句话说清项目是什么
- Topics：例如 `ai-agent`、`spring-boot`、`vue`、`sqlite`、`local-first`
- Website：如果后续有文档站或演示页再补
- Social preview：上传一张仓库分享封面图

## 2. 默认分支与保护

建议：

- 默认分支用 `develop` 或 `main` 二选一，不要长期混用
- 至少给默认分支打开保护规则：
  - Require a pull request before merging
  - Require status checks to pass
  - Block force pushes

如果你平时自己开发也走 PR，这套规则能显著减少误操作。

## 3. 合并策略

个人项目建议优先开启：

- Squash merge

可选关闭：

- Merge commit
- Rebase merge

这样主线历史会更干净。

## 4. Issue 与 PR

仓库里已经加了 Issue 表单和 PR 模板。你在 GitHub 网页上还可以继续做两件事：

- 开启 Issues
- 配一组常用 Labels，例如：
  - `bug`
  - `enhancement`
  - `docs`
  - `question`
  - `good first issue`
  - `help wanted`

## 5. Actions 与依赖更新

仓库里已经加了：

- GitHub Actions CI
- Dependabot

你上线后需要做的只有：

- 到 Actions 页面确认工作流第一次运行成功
- 到 Insights / Dependency graph 里确认 Dependabot 已生效

## 6. Release 与版本

如果项目开始对外发布，建议形成最小发布习惯：

1. 用 tag 标版本，例如 `v0.2.0`
2. 每次发版写清楚：
   - 新增能力
   - 破坏性变更
   - 升级步骤

## 7. 安全与反馈

建议开启：

- Security advisories
- Dependency graph
- Dependabot alerts

同时保持：

- `SECURITY.md` 告诉别人如何私下报告漏洞
- `SUPPORT.md` 告诉别人普通问题去哪里提

## 8. 你现在最应该先做的事

如果时间有限，优先级建议是：

1. 确认默认分支
2. 打开分支保护
3. 跑通 CI
4. 补仓库描述与 topics
5. 开始用 Release 管理版本
