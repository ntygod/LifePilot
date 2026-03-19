---
name: coding-agent
description: 'Delegate coding tasks to Codex, Claude Code, or Pi agents via background process. Use when: (1) building/creating new features or apps, (2) reviewing PRs (spawn in temp dir), (3) refactoring large codebases, (4) iterative coding that needs file exploration. NOT for: simple one-liner fixes (just edit), reading code (use read tool), thread-bound ACP harness requests in chat (for example spawn/run Codex or Claude Code in a Discord thread; use sessions_spawn with runtime:"acp"), or any work in ~/clawd workspace (never spawn agents here). Claude Code: use --print --permission-mode bypassPermissions (no PTY). Codex/Pi/OpenCode: pty:true required.'
metadata:
  {
    "openclaw": { "emoji": "🧩", "requires": { "anyBins": ["claude", "codex", "opencode", "pi"] } },
  }
---

# Coding Agent (bash-first)

Use **bash** (with optional background mode) for all coding agent work. Simple and effective.

## PTY Mode: Codex/Pi/OpenCode yes, Claude Code no

For **Codex, Pi, and OpenCode**, PTY is still required (interactive terminal apps):

```bash
bash pty:true command:"codex exec 'Your prompt'"
```

For **Claude Code** (`claude` CLI), use `--print --permission-mode bypassPermissions` instead.

```bash
cd /path/to/project && claude --permission-mode bypassPermissions --print 'Your task'
```

### Bash Tool Parameters

| Parameter    | Type    | Description                                                                 |
| ------------ | ------- | --------------------------------------------------------------------------- |
| `command`    | string  | The shell command to run                                                    |
| `pty`        | boolean | Allocates a pseudo-terminal for interactive CLIs |
| `workdir`    | string  | Working directory                   |
| `background` | boolean | Run in background, returns sessionId for monitoring                         |
| `timeout`    | number  | Timeout in seconds                                |
| `elevated`   | boolean | Run on host instead of sandbox                 |

### Process Tool Actions (for background sessions)

| Action      | Description                                          |
| ----------- | ---------------------------------------------------- |
| `list`      | List all running/recent sessions                     |
| `poll`      | Check if session is still running                    |
| `log`       | Get session output (with optional offset/limit)      |
| `write`     | Send raw data to stdin                               |
| `submit`    | Send data + newline                               |
| `send-keys` | Send key tokens or hex bytes                         |
| `paste`     | Paste text                            |
| `kill`      | Terminate the session                                |

## The Pattern: workdir + background + pty

```bash
bash pty:true workdir:~/project background:true command:"codex exec --full-auto 'Build a snake game'"
# Returns sessionId for tracking

process action:log sessionId:XXX
process action:poll sessionId:XXX
process action:write sessionId:XXX data:"y"
process action:submit sessionId:XXX data:"yes"
process action:kill sessionId:XXX
```

## Codex CLI

| Flag            | Effect                                             |
| --------------- | -------------------------------------------------- |
| `exec "prompt"` | One-shot execution, exits when done                |
| `--full-auto`   | Sandboxed but auto-approves in workspace           |
| `--yolo`        | NO sandbox, NO approvals                |

## Claude Code

```bash
bash workdir:~/project background:true command:"claude --permission-mode bypassPermissions --print 'Your task'"
```

## Parallel Issue Fixing with git worktrees

```bash
git worktree add -b fix/issue-78 /tmp/issue-78 main
bash pty:true workdir:/tmp/issue-78 background:true command:"codex --yolo 'Fix issue #78'"
process action:list
```

## Rules

1. Use the right execution mode per agent (Codex: pty, Claude Code: --print)
2. Respect tool choice - if user asks for Codex, use Codex
3. Be patient - don't kill sessions because they're "slow"
4. Monitor with process:log
5. Parallel is OK - run many processes at once for batch work

## Progress Updates

- Send 1 short message when you start
- Update on milestones, questions, errors, completion
- If you kill a session, say why
