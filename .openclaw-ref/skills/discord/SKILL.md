---
name: discord
description: "Discord ops via the message tool (channel=discord)."
---

# Discord (Via `message`)

Use the `message` tool. Always: `channel: "discord"`.

## Common Actions
- Send: `{ "action": "send", "channel": "discord", "to": "channel:123", "message": "hello" }`
- React: `{ "action": "react", "channelId": "123", "messageId": "456", "emoji": "✅" }`
- Read: `{ "action": "read", "to": "channel:123", "limit": 20 }`
- Edit/Delete: by channelId + messageId
- Poll, Pins, Threads, Search supported

## Writing Style
- Short, conversational, low ceremony
- No markdown tables
- Mention users as `<@USER_ID>`
