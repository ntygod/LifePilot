---
name: session-logs
description: Search and analyze your own session logs (older/parent conversations) using jq.
---

# session-logs

Search complete conversation history stored in session JSONL files.

## Location
Session logs: `~/.openclaw/agents/<agentId>/sessions/`
- `sessions.json` — Index mapping session keys to session IDs
- `<session-id>.jsonl` — Full conversation transcript per session

## Common Queries

### List all sessions by date
```bash
for f in ~/.openclaw/agents/<agentId>/sessions/*.jsonl; do
  date=$(head -1 "$f" | jq -r '.timestamp' | cut -dT -f1)
  size=$(ls -lh "$f" | awk '{print $5}')
  echo "$date $size $(basename $f)"
done | sort -r
```

### Extract user messages
```bash
jq -r 'select(.message.role == "user") | .message.content[]? | select(.type == "text") | .text' <session>.jsonl
```

### Search keyword in assistant responses
```bash
jq -r 'select(.message.role == "assistant") | .message.content[]? | select(.type == "text") | .text' <session>.jsonl | rg -i "keyword"
```

### Search across ALL sessions
```bash
rg -l "phrase" ~/.openclaw/agents/<agentId>/sessions/*.jsonl
```
