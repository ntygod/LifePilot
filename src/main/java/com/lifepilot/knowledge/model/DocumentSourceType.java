package com.lifepilot.knowledge.model;

/**
 * 知识文档来源类型。
 *
 * @author zsg
 * @since 2026-03-26
 */
public enum DocumentSourceType {

    /** 通过知识库上传的文件文档。 */
    FILE,

    /** 由 datastore 结构化数据同步生成的文档。 */
    DATASTORE_DOCUMENT
}
