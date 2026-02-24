package com.lifepilot.memory.semantic;

/**
 * 关系类型常量 — 管理已知关系类型字符串。
 *
 * <p>所有常量为 {@code public static final String}，值为 snake_case 格式。
 * 私有构造函数禁止实例化。</p>
 *
 * @author zsg
 * @since 2026-02-25
 */
public final class RelationTypes {

    // 社交关系
    public static final String KNOWS = "knows";
    public static final String IS_FRIEND_OF = "is_friend_of";
    public static final String IS_FAMILY_OF = "is_family_of";
    public static final String IS_CLIENT_OF = "is_client_of";

    // 组织关系
    public static final String WORKS_AT = "works_at";
    public static final String BELONGS_TO = "belongs_to";
    public static final String MANAGES = "manages";
    public static final String REPORTS_TO = "reports_to";

    // 项目关系
    public static final String RESPONSIBLE_FOR = "responsible_for";
    public static final String PARTICIPATES_IN = "participates_in";
    public static final String HAS_MILESTONE = "has_milestone";
    public static final String DEPENDS_ON = "depends_on";

    // 因果关系
    public static final String CAUSED_BY = "caused_by";
    public static final String LEADS_TO = "leads_to";
    public static final String RELATED_TO = "related_to";

    // 偏好关系
    public static final String PREFERS = "prefers";
    public static final String DISLIKES = "dislikes";
    public static final String HAS_GOAL = "has_goal";
    public static final String HAS_HABIT = "has_habit";

    private RelationTypes() {}
}
