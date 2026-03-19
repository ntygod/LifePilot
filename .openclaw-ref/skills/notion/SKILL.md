---
name: notion
description: Notion API for creating and managing pages, databases, and blocks.
---

# Notion

Use the Notion API to create/read/update pages, data sources (databases), and blocks.

## API Basics
```bash
NOTION_KEY=$(cat ~/.config/notion/api_key)
curl -X GET "https://api.notion.com/v1/..." \
  -H "Authorization: Bearer $NOTION_KEY" \
  -H "Notion-Version: 2025-09-03" \
  -H "Content-Type: application/json"
```

## Common Operations
- Search: `POST /v1/search`
- Get page: `GET /v1/pages/{page_id}`
- Get blocks: `GET /v1/blocks/{page_id}/children`
- Create page: `POST /v1/pages`
- Query database: `POST /v1/data_sources/{id}/query`
- Update page: `PATCH /v1/pages/{page_id}`
- Add blocks: `PATCH /v1/blocks/{page_id}/children`

## Property Types
- Title: `{"title": [{"text": {"content": "..."}}]}`
- Select: `{"select": {"name": "Option"}}`
- Date: `{"date": {"start": "2024-01-15"}}`
- Checkbox: `{"checkbox": true}`
