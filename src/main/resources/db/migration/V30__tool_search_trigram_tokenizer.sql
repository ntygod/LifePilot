-- 切换工具搜索 FTS5 索引到 trigram tokenizer。
--
-- unicode61 默认把连续 CJK 文本视作单个 token（"删除文件" 是 1 个 token），
-- 中文 query 必须整体 token 完全匹配才命中。trigram 用 3 字符滑窗，
-- 索引和 query 双向 substring 匹配，更适合中文短语命中。
--
-- 配套约定：description / tags 写作 LLM/用户视角的自然短语，
-- 把高频 query 短语（如"删除文件""复制目录"）显式落在 description / tags 里，
-- 让 trigram 能直接 substring 命中。
--
-- 启动期 ToolSearchIndexBuilder 会全量重建索引内容。

DROP TABLE IF EXISTS tool_search_index;

CREATE VIRTUAL TABLE tool_search_index USING fts5(
    tool_id UNINDEXED,
    description,
    tags,
    actions,
    category,
    tokenize = 'trigram'
);
