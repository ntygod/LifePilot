package com.lifepilot.knowledge.model;

/**
 * 文档处理状态枚举。
 *
 * <p>定义文档在知识库中从上传到就绪（或错误）的完整生命周期状态。
 * 提供终态判定 {@link #isTerminal()} 和可重试判定 {@link #isRetryable()} 方法。
 *
 * @author zsg
 * @since 2026-02-25
 */
public enum DocumentStatus {

    UPLOADING("上传中"),
    PARSING("解析中"),
    CHUNKING("分块中"),
    INDEXING("索引中"),
    EXTRACTING("提取中"),
    READY("就绪"),
    UPDATING("更新中"),
    DELETING("删除中"),
    ERROR("错误");

    private final String displayName;

    DocumentStatus(String displayName) {
        this.displayName = displayName;
    }

    /**
     * 返回状态的中文显示名称。
     *
     * @return 中文显示名称
     */
    public String displayName() {
        return displayName;
    }

    /**
     * 判断是否为终态（READY 或 ERROR）。
     *
     * @return 终态返回 true
     */
    public boolean isTerminal() {
        return this == READY || this == ERROR;
    }

    /**
     * 判断是否可重试（仅 ERROR 可重试）。
     *
     * @return 可重试返回 true
     */
    public boolean isRetryable() {
        return this == ERROR;
    }
}
