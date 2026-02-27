package com.lifepilot.a2a.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A2A 消息角色枚举。
 *
 * @author zsg
 * @since 2026-02-28
 */
@JsonFormat(shape = JsonFormat.Shape.STRING)
public enum A2aRole {

    @JsonProperty("user") USER,
    @JsonProperty("agent") AGENT
}
