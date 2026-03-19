---
name: slack
description: Use when you need to control Slack from OpenClaw via the slack tool.
---

# Slack Actions

## Actions
- react, reactions, sendMessage, editMessage, deleteMessage
- readMessages, pinMessage, unpinMessage, listPins
- memberInfo, emojiList

## Example
```json
{ "action": "sendMessage", "to": "channel:C123", "content": "Hello from OpenClaw" }
{ "action": "react", "channelId": "C123", "messageId": "1712023032.1234", "emoji": "✅" }
```
