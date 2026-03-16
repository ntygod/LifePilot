package com.lifepilot.tool.model;

/**
 * 工具元能力分组 — 概括万能 Agent 的所有能力维度。
 *
 * <p>7 个分组覆盖 Agent 与世界交互的全部方式：
 * 感知世界、改变世界、思考推理、持久存储、人机交互、自我认知、能力扩展。</p>
 *
 * @author zsg
 * @since 2026-03-16
 */
public enum ToolCategory {

    /** 感知 — 获取外部信息（环境感知、Web 搜索/抓取、浏览器读取）。 */
    PERCEPTION("感知", "获取外部世界的信息"),

    /** 行动 — 改变外部状态（文件写入、Shell 执行、浏览器操作、代码执行）。 */
    ACTION("行动", "改变外部世界的状态"),

    /** 认知 — 内部推理与计算（精确计算、think 推理）。 */
    COGNITION("认知", "内部推理与计算"),

    /** 存储 — 持久化数据（DataStore CRUD、集合管理、聚合查询）。 */
    STORAGE("存储", "持久化和检索数据"),

    /** 交互 — 人机对话（选择、输入、通知）。 */
    INTERACTION("交互", "与用户进行对话交互"),

    /** 自省 — 了解自身能力（list-capabilities、explain、status、suggest）。 */
    INTROSPECTION("自省", "了解和查询自身能力"),

    /** 扩展 — 获取新能力（Skill 发现/激活、MCP 安装）。 */
    EXTENSION("扩展", "发现和获取新的能力");

    private final String displayName;
    private final String description;

    ToolCategory(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    /** 获取中文显示名称。 */
    public String displayName() {
        return displayName;
    }

    /** 获取中文描述。 */
    public String description() {
        return description;
    }
}
