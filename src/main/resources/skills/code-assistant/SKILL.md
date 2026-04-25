---
name: code-assistant
description: 当用户要通过外部编码 CLI（Claude Code / Codex / Gemini）完成多文件开发、修 bug、跨模块重构、PR 审查、并行任务分发等需要后台 Agent 执行的复杂编码任务时使用。关键词：写代码、开发功能、修 bug、修复 bug、重构、代码审查、PR 审查、后台跑、编码 Agent、并行审查、并行开发、Claude Code、Codex。单文件小改直接用 file.write/file.edit，仅读代码用 file.read，跑脚本用 shell.exec 或 code.execute。
version: 2.8.0
metadata:
  zhiwei:
    category: automation
    priority: normal
    tags:
      - code
      - cli
      - claude-code
      - codex
      - refactor
      - code-review
      - orchestration
    suggested_tools:
      - shell.exec
      - shell.process
      - file.read
      - file.write
      - file.list
      - file.edit
      - git.query
      - git.mutate
---

# 编码代理指南

知微作为调度层，启动 Claude Code / Codex 等外部 CLI 作为子 Agent，后台执行复杂编码任务，通过 `shell.process` 监控进度、收集产出，最后汇总交付。

## 适用场景

- 多文件开发、跨模块重构
- PR 审查（和实现使用不同 CLI 可互相纠偏）
- 并行任务分发（多个 issue、多个模块、多方案探索）
- 串行编排（实现→审查、设计→实现、迁移→验证）
- 反馈环迭代（审查→修→再审查）

## 不适用场景

- 单文件小改 → 用 `file.edit`
- 仅读代码 → 用 `file.read`
- 跑脚本 → 用 `shell.exec` 或 `code.execute`

## 工作流

1. **启动前验证**：`claude --version` / `claude /status`；未装/未登录提示用户，不代操作
2. **破坏性任务隔离**：必须在 git worktree 或临时目录，不在主工作目录自由改动
3. **启动 Agent**：`shell.exec` 带 `background=true`，立即调 `shell.process output` 验证启动
4. **监控轮询**（5-15 秒）：读 `lastResult` 判断结束；`type=assistant` 的 `tool_use` 提当前动作；错误信号立即报
5. **汇报节奏**：启动一条、里程碑更新、异常立即报，**不要每轮汇报**
6. **完成**：COMPLETED + exitCode=0 → 读 result + `git diff` 看改动
7. **编排**：串行（前序 exitCode=0 才启后序）/ 并行（独立 worktree）/ 反馈环（硬上限 2-3 轮）

## 详细参考

- 启动 / 监控 / 编排模式完整流程：`{skill_dir}/references/agent-lifecycle.md`
- CLI 选择原则 / 上下文传递 / 汇总模板 / 安全红线 / 常见问题：`{skill_dir}/references/orchestration.md`
</content>
</invoke>