package com.lifepilot.workflow.model;

import java.util.List;

import org.springframework.lang.Nullable;

/**
 * 工作流输入参数定义。
 *
 * <p>描述工作流接受的单个输入参数的元信息，包括名称、类型、是否必填、默认值和描述。
 * 支持的类型：{@code string / number / boolean / list / map}。
 *
 * @param name              参数名称
 * @param type              参数类型（string / number / boolean / list / map）
 * @param required          是否必填
 * @param defaultValue      默认值，为 null 表示无默认值
 * @param description       参数描述，为 null 表示无描述
 * @param inputType         输入控件类型（select / text / number / boolean / textarea），为 null 表示由前端自动推断
 * @param options           枚举选项列表，仅当 inputType 为 select 时有效
 * @param placeholder       输入提示占位符
 * @param example           示例值
 * @param validationPattern 正则校验规则
 * @param validationMessage 校验失败提示消息
 * @author zsg
 * @since 2026-02-26
 */
public record WorkflowInputParam(
        String name,
        String type,
        boolean required,
        @Nullable Object defaultValue,
        @Nullable String description,
        @Nullable String inputType,
        @Nullable List<OptionItem> options,
        @Nullable String placeholder,
        @Nullable String example,
        @Nullable String validationPattern,
        @Nullable String validationMessage
) {}
