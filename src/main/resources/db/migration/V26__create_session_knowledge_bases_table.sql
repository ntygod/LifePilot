-- V26: 创建会话-知识库关联表
-- 用于支持会话关联多个知识库的功能

CREATE TABLE IF NOT EXISTS session_knowledge_bases (
    session_id TEXT NOT NULL,
    knowledge_base_id TEXT NOT NULL,
    PRIMARY KEY (session_id, knowledge_base_id),
    FOREIGN KEY (session_id) REFERENCES chat_sessions(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_session_knowledge_bases_session 
    ON session_knowledge_bases(session_id);

CREATE INDEX IF NOT EXISTS idx_session_knowledge_bases_kb 
    ON session_knowledge_bases(knowledge_base_id);
