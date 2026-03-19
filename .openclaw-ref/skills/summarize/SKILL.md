---
name: summarize
description: Summarize or extract text/transcripts from URLs, podcasts, and local files.
---

# Summarize

Fast CLI to summarize URLs, local files, and YouTube links.

## When to use (trigger phrases)
- "what's this link/video about?"
- "summarize this URL/article"
- "transcribe this YouTube/video"

## Quick start
```bash
summarize "https://example.com" --model google/gemini-3-flash-preview
summarize "/path/to/file.pdf" --model google/gemini-3-flash-preview
```

## Useful flags
- `--length short|medium|long|xl|xxl|<chars>`
- `--max-output-tokens <count>`
- `--extract-only` (URLs only)
- `--json` (machine readable)
