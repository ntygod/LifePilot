---
name: healthcheck
description: Host security hardening and risk-tolerance configuration for OpenClaw deployments.
---

# OpenClaw Host Hardening

## Workflow (follow in order)
0. Model self-check (non-blocking)
1. Establish context (read-only) — OS, privilege, access path, network exposure
2. Run OpenClaw security audits — `openclaw security audit --deep`
3. Check version/update status — `openclaw update status`
4. Determine risk tolerance — Home Balanced / VPS Hardened / Developer Convenience / Custom
5. Produce remediation plan
6. Offer execution options — Do it for me / Show plan only / Fix critical only / Export
7. Execute with confirmations
8. Verify and report

## Key Principles
- Require explicit approval before state-changing actions
- Prefer reversible, staged changes with rollback plan
- Never claim OpenClaw changes host firewall/SSH/OS updates
- Use numbered choices for user selections
