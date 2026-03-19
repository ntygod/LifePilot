# OpenClaw System Prompt Design

> Source: https://molty.finna.ai/docs/concepts/system-prompt

## Structure

The prompt is intentionally compact and uses fixed sections:

1. **Tooling**: current tool list + short descriptions
2. **Safety**: short guardrail reminder (advisory, not enforcement)
3. **Skills** (when available): tells model how to load skill instructions on demand
4. **OpenClaw Self-Update**: how to run config.apply and update.run
5. **Workspace**: working directory
6. **Documentation**: local docs path + public mirror + ClawHub
7. **Workspace Files (injected)**: bootstrap files included below
8. **Sandbox** (when enabled): sandboxed runtime info
9. **Current Date & Time**: user-local time, timezone (no dynamic clock for cache stability)
10. **Reply Tags**: optional reply tag syntax
11. **Heartbeats**: heartbeat prompt and ack behavior
12. **Runtime**: host, OS, node, model, repo root, thinking level (one line)
13. **Reasoning**: current visibility level + /reasoning toggle hint

## Prompt Modes

- **full** (default): all sections
- **minimal** (sub-agents): omits Skills, Memory Recall, Self-Update, Model Aliases, User Identity, Reply Tags, Messaging, Silent Replies, Heartbeats
- **none**: only base identity line

## Tool Call Style (from source code)

Key principle in system prompt:
- Don't narrate routine low-risk tool calls, just call them directly
- Only briefly explain intent for: multi-step complex operations, sensitive operations (delete, overwrite), when user explicitly asks for explanation
- Keep narration concise, avoid repeating obvious steps
- For complex/time-consuming tasks: prefer loading corresponding Skill for operation guide rather than executing from memory

## Skills in Prompt

```xml
<available_skills>
  <skill>
    <name>...</name>
    <description>...</description>
    <location>...</location>
  </skill>
</available_skills>
```

Model uses `read` tool to load SKILL.md at listed location. This is **selective injection** — prompt only contains summary, full instructions loaded on demand.

## Bootstrap Injection

Files injected into context on every turn:
- AGENTS.md — Agent definition and behavior guide
- SOUL.md — Personality and style guide
- TOOLS.md — Tool usage guide (local notes)
- IDENTITY.md — Identity info
- USER.md — User preferences
- HEARTBEAT.md — Heartbeat task checklist
- MEMORY.md — Long-term memory

Large files truncated at `bootstrapMaxChars` (default: 20000).
Sub-agents only inject AGENTS.md and TOOLS.md.
