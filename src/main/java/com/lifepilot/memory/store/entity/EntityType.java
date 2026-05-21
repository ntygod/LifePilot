package com.lifepilot.memory.store.entity;

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
    EXPERIENCE,
    CUSTOM;

    /** 返回中文标签，用于 textRepresentation() 自然语言生成。 */
    public String label() {
        return switch (this) {
            case PERSON -> "人物";
            case ORGANIZATION -> "组织";
            case PLACE -> "地点";
            case EVENT -> "事件";
            case PROJECT -> "项目";
            case TOPIC -> "话题";
            case PREFERENCE -> "偏好";
            case HABIT -> "习惯";
            case GOAL -> "目标";
            case SKILL -> "技能";
            case EXPERIENCE -> "经验";
            case CUSTOM -> "自定义";
        };
    }
}
