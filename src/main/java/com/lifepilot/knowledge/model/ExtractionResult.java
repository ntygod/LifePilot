package com.lifepilot.knowledge.model;

import java.util.List;

/**
 * 知识提取结果 — 记录实体和关系提取的统计信息。
 *
 * @param entityCount   提取的实体数量
 * @param relationCount 提取的关系数量
 * @param warnings      提取过程中的警告信息
 * @author zsg
 * @since 2026-02-25
 */
public record ExtractionResult(int entityCount, int relationCount, List<String> warnings) {}
