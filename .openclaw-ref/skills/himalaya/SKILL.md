---
name: himalaya
description: "CLI to manage emails via IMAP/SMTP."
---

# Himalaya Email CLI

## Common Operations

### List/Search
```bash
himalaya envelope list
himalaya envelope list --folder "Sent"
himalaya envelope list from john@example.com subject meeting
```

### Read/Reply/Forward
```bash
himalaya message read 42
himalaya message reply 42
himalaya message reply 42 --all
himalaya message forward 42
```

### Write/Send
```bash
himalaya message write
cat << 'EOF' | himalaya template send
From: you@example.com
To: recipient@example.com
Subject: Test
Hello!
EOF
```

### Move/Delete
```bash
himalaya message move 42 "Archive"
himalaya message delete 42
```

### Attachments
```bash
himalaya attachment download 42 --dir ~/Downloads
```
