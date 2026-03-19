---
name: clawhub
description: Use the ClawHub CLI to search, install, update, and publish agent skills from clawhub.com.
---

# ClawHub CLI

## Commands
```bash
clawhub search "postgres backups"
clawhub install my-skill
clawhub install my-skill --version 1.2.3
clawhub update my-skill
clawhub update --all
clawhub list
clawhub publish ./my-skill --slug my-skill --name "My Skill" --version 1.2.0
```

## Notes
- Default registry: https://clawhub.com
- Default install dir: ./skills
