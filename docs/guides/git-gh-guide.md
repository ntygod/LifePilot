# Git 与 GitHub CLI 速查表

> 适合第一次维护本仓库时快速查命令、查流程。

## 1. 分清 `git` 和 `gh`

- `git`：管理本地代码、分支、提交、同步
- `gh`：管理 GitHub 上的 PR、Issue、Actions、Release

可以把它理解成：

- `git` 负责“代码仓库内容”
- `gh` 负责“GitHub 平台操作”

## 2. 首次配置

先确认本机已经安装 GitHub CLI：

```bash
gh --version
```

首次登录：

```bash
gh auth login
gh auth status
```

建议顺手补一下 Git 提交身份：

```bash
git config --global user.name "你的名字"
git config --global user.email "你的邮箱"
```

## 3. 本仓库的默认协作规则

当前仓库建议按这套规则工作：

- 主开发分支：`develop`
- 日常开发分支：`feature/{name}` 或 `bugfix/{name}`
- 合并方式：`Squash merge`
- 默认通过 PR 合入，不直接往 `develop` 推代码
- 合并前至少通过两个检查：
  - `Backend Test`
  - `Frontend Test and Build`

## 4. 标准 PR 流程

### 4.1 从最新 `develop` 开分支

```bash
git switch develop
git pull --ff-only origin develop
git switch -c feature/my-change
```

### 4.2 本地开发并验证

```bash
mvn test
cd zhiwei-web
npm run test:run
npm run build
```

### 4.3 提交并推送

```bash
git add .
git commit -m "feat(core): 中文描述"
git push -u origin feature/my-change
```

### 4.4 创建 PR

```bash
gh pr create --base develop --fill
```

如果你想手动写标题和说明，也可以：

```bash
gh pr create --base develop --title "feat(core): 中文描述" --body "变更说明"
```

### 4.5 查看 CI 状态

```bash
gh pr list
gh pr view 15
gh pr checks 15 --watch
```

### 4.6 分支落后时更新 PR

如果 GitHub 提示 “the head branch is not up to date with the base branch”，说明你的 PR 分支落后于 `develop`，需要先更新：

```bash
gh pr update-branch 15 --rebase
gh pr checks 15 --watch
```

### 4.7 合并 PR

```bash
gh pr merge 15 --squash --delete-branch
```

### 4.8 同步本地 `develop`

```bash
git switch develop
git pull --ff-only origin develop
```

## 5. 日常最常用命令

### 5.1 看仓库和分支

```bash
git status --short --branch
git branch
git log --oneline --decorate -10
gh repo view
```

### 5.2 看 PR

```bash
gh pr list
gh pr view 15
gh pr checkout 15
gh pr diff 15
```

### 5.3 看 CI

```bash
gh pr checks 15
gh pr checks 15 --watch
gh run list
gh run view <run-id> --log-failed
```

### 5.4 看 Issue

```bash
gh issue list
gh issue view 3
gh issue create
```

## 6. 怎么判断一个 PR 能不能合

一般只看这三件事：

1. `gh pr view <pr号>`：确认目标分支是 `develop`
2. `gh pr checks <pr号>`：确认 `Backend Test` 和 `Frontend Test and Build` 都通过
3. 看是否提示分支落后：如果落后，先执行 `gh pr update-branch <pr号> --rebase`

经验判断可以直接记成：

- `双绿 + 分支最新`：可以合
- `双绿 + behind`：先更新分支，再重跑一次
- `有 fail`：先看日志，不要直接合

## 7. 当前仓库的 CI 行为

现在仓库的 CI 规则是：

- PR 仍然固定产出两个 required checks：
  - `Backend Test`
  - `Frontend Test and Build`
- 但内部会先判定改动范围，再决定是否真的执行完整测试
- 纯文档、README、Issue 模板这类改动，会直接快速跳过前后端重活，但 required checks 仍然会成功结束
- 改了 `.github/workflows/`、无法安全归类的共享文件，默认执行前后端完整 CI
- `workflow_dispatch` 和定时任务会走全量回归

这套设计的目的，是减少无意义的完整回归时间，同时不破坏分支保护规则。

## 8. 处理 Dependabot PR 的最小流程

Dependabot PR 主要是升级依赖。建议按下面这个节奏处理：

1. `gh pr view <pr号>` 看它升级了什么
2. `gh pr checks <pr号>` 看 CI 是否双绿
3. 如果分支落后，执行 `gh pr update-branch <pr号> --rebase`
4. 重跑后还是双绿，就执行：

```bash
gh pr merge <pr号> --squash --delete-branch
```

如果是大版本升级，最好再补一次人工验证。

## 9. 适合你现在的最小工作流

以后你可以几乎固定按这个顺序操作：

```bash
git switch develop
git pull --ff-only origin develop
git switch -c feature/my-change

# 改代码并测试

git add .
git commit -m "feat(core): 中文描述"
git push -u origin feature/my-change

gh pr create --base develop --fill
gh pr checks <pr号> --watch
gh pr merge <pr号> --squash --delete-branch

git switch develop
git pull --ff-only origin develop
```

## 10. 常见问题

### 10.1 为什么 CI 都绿了还是不能合？

通常是因为分支基线旧了。别直接重试合并，先更新：

```bash
gh pr update-branch <pr号> --rebase
```

### 10.2 为什么本地是最新的，GitHub 上 PR 还是显示冲突或落后？

因为 PR 检查的是远端分支，不是你本地当前工作区。你需要更新 PR 分支本身。

### 10.3 为什么合并后本地 `develop` 还是旧的？

因为 GitHub 上的合并不会自动更新你本地仓库。合并后要手动拉取：

```bash
git switch develop
git pull --ff-only origin develop
```

### 10.4 什么情况下不要直接合 Dependabot PR？

这些情况建议先人工确认：

- 大版本升级
- 涉及运行时、原生依赖、浏览器驱动
- 虽然双绿，但你怀疑会影响线上行为

## 11. 配套文档

- 仓库维护清单见 [GitHub 仓库维护指南](./github-maintainer-guide.md)
- 发布版本见 [Release 发布指南](./release-guide.md)
