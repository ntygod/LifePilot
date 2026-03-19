---
name: obsidian
description: Work with Obsidian vaults (plain Markdown notes) and automate via obsidian-cli.
---

# Obsidian

Obsidian vault = a normal folder on disk.

## Find the active vault(s)
- `obsidian-cli print-default --path-only`
- Or read `~/Library/Application Support/obsidian/obsidian.json`

## obsidian-cli quick start

### Search
- `obsidian-cli search "query"` (note names)
- `obsidian-cli search-content "query"` (inside notes)

### Create
- `obsidian-cli create "Folder/New note" --content "..." --open`

### Move/rename (safe refactor)
- `obsidian-cli move "old/path/note" "new/path/note"` (updates wikilinks)

### Delete
- `obsidian-cli delete "path/note"`

Prefer direct edits when appropriate: open the `.md` file and change it.
