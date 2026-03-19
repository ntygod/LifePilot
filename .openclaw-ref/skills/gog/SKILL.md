---
name: gog
description: Google Workspace CLI for Gmail, Calendar, Drive, Contacts, Sheets, and Docs.
---

# gog

Use `gog` for Gmail/Calendar/Drive/Contacts/Sheets/Docs. Requires OAuth setup.

## Setup (once)
- `gog auth credentials /path/to/client_secret.json`
- `gog auth add you@gmail.com --services gmail,calendar,drive,contacts,docs,sheets`

## Common commands
- Gmail search: `gog gmail search 'newer_than:7d' --max 10`
- Gmail send: `gog gmail send --to a@b.com --subject "Hi" --body "Hello"`
- Calendar list: `gog calendar events <calendarId> --from <iso> --to <iso>`
- Calendar create: `gog calendar create <calendarId> --summary "Title" --from <iso> --to <iso>`
- Drive search: `gog drive search "query" --max 10`
- Sheets get: `gog sheets get <sheetId> "Tab!A1:D10" --json`
- Docs export: `gog docs export <docId> --format txt --out /tmp/doc.txt`

## Notes
- Set `GOG_ACCOUNT=you@gmail.com` to avoid repeating `--account`
- Confirm before sending mail or creating events
