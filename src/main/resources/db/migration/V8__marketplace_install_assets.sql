ALTER TABLE installed_extensions
    ADD COLUMN install_root_path TEXT NOT NULL DEFAULT '';

ALTER TABLE installed_extensions
    ADD COLUMN assets_json TEXT;

UPDATE installed_extensions
SET install_root_path = file_path
WHERE install_root_path = '';
