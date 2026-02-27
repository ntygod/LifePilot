package com.lifepilot.a2a.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A2A Task 状态枚举。
 *
 * <p>JSON 序列化为小写字符串（如 "submitted"、"working"）。</p>
 *
 * @author zsg
 * @since 2026-02-28
 */
@JsonFormat(shape = JsonFormat.Shape.STRING)
public enum A2aTaskState {

    @JsonProperty("submitted") SUBMITTED,
    @JsonProperty("working") WORKING,
    @JsonProperty("input_required") INPUT_REQUIRED,
    @JsonProperty("completed") COMPLETED,
    @JsonProperty("canceled") CANCELED,
    @JsonProperty("failed") FAILED,
    @JsonProperty("rejected") REJECTED,
    @JsonProperty("auth_required") AUTH_REQUIRED;

    /**
     * 是否为终态（不可再转换）。
     *
     * @return 终态返回 true
     */
    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELED || this == REJECTED;
    }
}
