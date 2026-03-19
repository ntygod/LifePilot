---
name: tmux
description: Remote-control tmux sessions for interactive CLIs by sending keystrokes and scraping pane output.
---

# tmux Session Control

Control tmux sessions by sending keystrokes and reading output.

## When to Use
- Monitoring Claude/Codex sessions in tmux
- Sending input to interactive terminal applications
- Scraping output from long-running processes in tmux

## When NOT to Use
- Running one-off shell commands → use exec tool directly
- Starting new background processes → use exec with background:true
- Non-interactive scripts → use exec tool

## Common Commands

### Capture Output
```bash
tmux capture-pane -t shared -p | tail -20
tmux capture-pane -t shared -p -S -
```

### Send Keys
```bash
tmux send-keys -t shared "hello"
tmux send-keys -t shared "y" Enter
tmux send-keys -t shared C-c
```

### Session Management
```bash
tmux new-session -d -s newsession
tmux kill-session -t sessionname
```

## Sending Input Safely
```bash
tmux send-keys -t shared -l -- "Please apply the patch"
sleep 0.1
tmux send-keys -t shared Enter
```
