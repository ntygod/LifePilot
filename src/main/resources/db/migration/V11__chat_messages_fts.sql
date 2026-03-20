CREATE VIRTUAL TABLE chat_messages_fts USING fts5(
    content,
    content='chat_messages',
    content_rowid='rowid',
    tokenize='unicode61 remove_diacritics 2'
);

INSERT INTO chat_messages_fts(rowid, content)
SELECT rowid, content
FROM chat_messages;

CREATE TRIGGER trg_chat_messages_fts_ai
AFTER INSERT ON chat_messages
BEGIN
    INSERT INTO chat_messages_fts(rowid, content)
    VALUES (new.rowid, new.content);
END;

CREATE TRIGGER trg_chat_messages_fts_ad
AFTER DELETE ON chat_messages
BEGIN
    INSERT INTO chat_messages_fts(chat_messages_fts, rowid, content)
    VALUES ('delete', old.rowid, old.content);
END;

CREATE TRIGGER trg_chat_messages_fts_au
AFTER UPDATE ON chat_messages
BEGIN
    INSERT INTO chat_messages_fts(chat_messages_fts, rowid, content)
    VALUES ('delete', old.rowid, old.content);
    INSERT INTO chat_messages_fts(rowid, content)
    VALUES (new.rowid, new.content);
END;
