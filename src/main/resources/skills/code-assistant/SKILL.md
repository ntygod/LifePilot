---
name: code-assistant
description: 当用户要通过外部编码 CLI（Claude Code / Codex / Gemini）完成多文件开发、修 bug、跨模块重构、PR 审查、并行任务分发等需要后台 Agent 执行的复杂编码任务时使用。
version: 2.9.0
metadata:
  zhiwei:
    tags:
      - code
      - cli
      - claude-code
      - codex
      - refactor
      - code-review
      - orchestration
    suggested_tools:
      - shell_exec
      - shell_process
      - code
      - file_read
      - file_write
      - file_manage
---

# 编码代理指南

知微作为调度层启动外部编码 CLI（Claude Code / Codex / Gemini）作为子 Agent 后台执行,自己只负责编排、监控、汇总。

## 适用场景

- 多文件开发、跨模块重构
- PR 审查（实现与审查用不同 CLI 互相纠偏）
- 并行任务分发（多 issue / 多模块 / 多方案探索）
- 串行编排（实现→审查、设计→实现、迁移→验证）
- 反馈环迭代（审查→修→再审查）
- 中断恢复（"上次跑到哪了"）

## 不适用场景

- 单文件小改 → `file_write`
- 仅读代码 → `file_read`
- 跑脚本 → `shell_exec` / `code`

## 工作流（按用户表达分流）

| 用户表达 | 路径 |
|---|---|
| 单任务（一句话给一个目标） | 启动 → 监控 → 汇总 |
| 实现 + 审查 / 设计 + 实现 | 串行编排,前序产物喂后序 |
| 多 issue / 多方案对比 | 并行编排,各自独立 worktree |
| 审查→修→再审查 | 反馈环,硬上限 ≤2 轮 |
| "上次跑到哪了" / "还在跑吗" | `shell_process(action="list")` 列既有 sessionId,定位再决定看 / 杀 |

## 各路径决策点（本 Skill 独有）

- **CLI 选择**:用户没指定 → 优先和当前最熟悉的;审查阶段建议换不同 CLI 减少同模型盲区
- **隔离边界**:破坏性任务必须 worktree 或临时目录,不要在用户主工作目录跑;并行任务改同一文件必然冲突,必须各自独立 worktree
- **bypassPermissions / --full-auto**:仅在 worktree + 无未提交改动 + 用户明确授权 三条件齐全时才允许
- **API key 注入**:只走 `env` 参数,绝不写文件;kill / output 响应不要回显 env 内容
- **反馈环上限**:固定 ≤2 轮,超限停下问用户(模型在死循环里改 bug 越改越多见)
- **上下文交接**:Agent 间共享 worktree 时让后序自己读文件,不要把上一个 Agent 的完整 stream-json 塞进下一个 prompt

## 详细参考

- 启动 / 监控 / 编排完整流程与命令模板:`{skill_dir}/references/agent-lifecycle.md`
- CLI 速查、汇总模板、错误处理表:`{skill_dir}/references/orchestration.md`
