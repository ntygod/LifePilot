package com.lifepilot.memory.semantic;

/**
 * 实体类型枚举 — 定义时序知识图谱中的实体分类。
 *
 * <p>数据库中以 TEXT 存储枚举名称（{@link #name()}），新增类型无需 Schema 迁移。</p>
 *
 * @author zsg
 * @since 2026-02-25
 */
public enum EntityType {
    PERSON,
    ORGANIZATION,
    PLACE,
    EVENT,
    PROJECT,
    TOPIC,
    PREFERENCE,
    HABIT,
    GOAL,
    SKILL,
    CUSTOM
}
