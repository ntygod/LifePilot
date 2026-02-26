package com.lifepilot.workflow.model;

import org.springframework.lang.Nullable;

/**
 * 工作流输入参数定义。
 *
 * <p>描述工作流接受的单个输入参数的元信息，包括名称、类型、是否必填、默认值和描述。
 * 支持的类型：{@code string / number / boolean / list / map}。
 *
 * @param name         参数名称
 * @param type         参数类型（string / number / boolean / list / map）
 * @param required     是否必填
 * @param defaultValue 默认值，为 null 表示无默认值
 * @param description  参数描述，为 null 表示无描述
 * @author zsg
 * @since 2026-02-26
 */
public record WorkflowInputParam(
        String name,
        String type,
        boolean required,
        @Nullable Object defaultValue,
        @Nullable String description
) {}
