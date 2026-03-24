CREATE VIRTUAL TABLE session_transcript_entries_fts USING fts5(
    entry_id UNINDEXED,
    session_id UNINDEXED,
    role UNINDEXED,
    content,
    tokenize='unicode61 remove_diacritics 2'
);

INSERT INTO session_transcript_entries_fts(rowid, entry_id, session_id, role, content)
SELECT rowid,
       id,
       session_id,
       COALESCE(role, ''),
       json_extract(payload_json, '$.content')
FROM session_transcript_entries
WHERE entry_type = 'message'
  AND visible_to_user = 1
  AND trim(COALESCE(json_extract(payload_json, '$.content'), '')) <> '';

CREATE TRIGGER trg_session_transcript_entries_fts_ai
AFTER INSERT ON session_transcript_entries
BEGIN
    INSERT INTO session_transcript_entries_fts(rowid, entry_id, session_id, role, content)
    SELECT new.rowid,
           new.id,
           new.session_id,
           COALESCE(new.role, ''),
           json_extract(new.payload_json, '$.content')
    WHERE new.entry_type = 'message'
      AND new.visible_to_user = 1
      AND trim(COALESCE(json_extract(new.payload_json, '$.content'), '')) <> '';
END;

CREATE TRIGGER trg_session_transcript_entries_fts_ad
AFTER DELETE ON session_transcript_entries
BEGIN
    INSERT INTO session_transcript_entries_fts(
        session_transcript_entries_fts,
        rowid,
        entry_id,
        session_id,
        role,
        content
    )
    SELECT 'delete',
           old.rowid,
           old.id,
           old.session_id,
           COALESCE(old.role, ''),
           json_extract(old.payload_json, '$.content')
    WHERE old.entry_type = 'message'
      AND old.visible_to_user = 1
      AND trim(COALESCE(json_extract(old.payload_json, '$.content'), '')) <> '';
END;

CREATE TRIGGER trg_session_transcript_entries_fts_au
AFTER UPDATE ON session_transcript_entries
BEGIN
    INSERT INTO session_transcript_entries_fts(
        session_transcript_entries_fts,
        rowid,
        entry_id,
        session_id,
        role,
        content
    )
    SELECT 'delete',
           old.rowid,
           old.id,
           old.session_id,
           COALESCE(old.role, ''),
           json_extract(old.payload_json, '$.content')
    WHERE old.entry_type = 'message'
      AND old.visible_to_user = 1
      AND trim(COALESCE(json_extract(old.payload_json, '$.content'), '')) <> '';

    INSERT INTO session_transcript_entries_fts(rowid, entry_id, session_id, role, content)
    SELECT new.rowid,
           new.id,
           new.session_id,
           COALESCE(new.role, ''),
           json_extract(new.payload_json, '$.content')
    WHERE new.entry_type = 'message'
      AND new.visible_to_user = 1
      AND trim(COALESCE(json_extract(new.payload_json, '$.content'), '')) <> '';
END;
