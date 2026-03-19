---
name: github
description: "GitHub operations via `gh` CLI: issues, PRs, CI runs, code review, API queries."
---

# GitHub Skill

Use the `gh` CLI to interact with GitHub repositories, issues, PRs, and CI.

## When to Use
- Checking PR status, reviews, or merge readiness
- Viewing CI/workflow run status and logs
- Creating, closing, or commenting on issues
- Creating or merging pull requests
- Querying GitHub API for repository data

## When NOT to Use
- Local git operations → use `git` directly
- Non-GitHub repos → different CLIs
- Reviewing actual code changes → use `coding-agent` skill

## Common Commands

### Pull Requests
```bash
gh pr list --repo owner/repo
gh pr checks 55 --repo owner/repo
gh pr view 55 --repo owner/repo
gh pr create --title "feat: add feature" --body "Description"
gh pr merge 55 --squash --repo owner/repo
```

### Issues
```bash
gh issue list --repo owner/repo --state open
gh issue create --title "Bug: something broken" --body "Details..."
gh issue close 42 --repo owner/repo
```

### CI/Workflow Runs
```bash
gh run list --repo owner/repo --limit 10
gh run view <run-id> --repo owner/repo
gh run view <run-id> --repo owner/repo --log-failed
gh run rerun <run-id> --failed --repo owner/repo
```

### API Queries
```bash
gh api repos/owner/repo/pulls/55 --jq '.title, .state, .user.login'
```

## Notes
- Always specify `--repo owner/repo` when not in a git directory
- Use `--json` + `--jq` for structured output
