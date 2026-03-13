package com.lifepilot.workflow.model;

/**
 * 参数枚举选项。
 *
 * <p>用于 {@link WorkflowInputParam#options()} 中描述下拉选择器的可选值。
 *
 * @param value 选项值（实际传入工作流的值）
 * @param label 选项标签（前端展示用）
 * @author zsg
 * @since 2026-03-13
 */
public record OptionItem(String value, String label) {}
