package com.lifepilot.modelservice.model;

/**
 * 模型服务类型。
 *
 * @author zsg
 * @since 2026-03-24
 */
public enum ModelServiceKind {
    /** 生成类服务。 */
    GENERATION,
    /** 向量化服务。 */
    EMBEDDING,
    /** 精排服务。 */
    RERANK
}
