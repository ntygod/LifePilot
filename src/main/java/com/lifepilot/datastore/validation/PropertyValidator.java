package com.lifepilot.datastore.validation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.datastore.model.PropertyDefinition;
import com.lifepilot.datastore.model.PropertyType;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * 属性校验器 — 校验文档 JSON 是否符合集合的属性定义。
 *
 * <p>校验规则：</p>
 * <ul>
 *   <li>required=true 的属性必须存在且非 null</li>
 *   <li>PropertyType 类型校验：TEXT→String, NUMBER→Number, BOOLEAN→Boolean,
 *       DATE→ISO 8601 日期, DATETIME→ISO 8601 日期时间, SELECT→String,
 *       MULTI_SELECT→Array, URL→String(URL格式), JSON→Object/Array</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-03-10
 */
public class PropertyValidator {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 校验文档 JSON 是否符合集合的属性定义。
     *
     * @param properties 属性定义列表
     * @param dataJson   文档 JSON 字符串
     * @return 校验错误列表（空列表表示通过）
     */
    public List<String> validate(List<PropertyDefinition> properties, String dataJson) {
        if (properties == null || properties.isEmpty()) {
            return List.of();
        }

        var errors = new ArrayList<String>();

        // 解析 JSON
        JsonNode root;
        try {
            root = MAPPER.readTree(dataJson);
        } catch (JsonProcessingException e) {
            return List.of("无效的 JSON 数据: " + e.getMessage());
        }

        if (root == null || !root.isObject()) {
            return List.of("文档数据必须是 JSON 对象");
        }

        for (var prop : properties) {
            var name = prop.name();
            var node = root.get(name);

            // 必填校验
            if (prop.required() && (node == null || node.isNull())) {
                errors.add(name + ": 缺少必需属性");
                continue;
            }

            // 属性不存在或为 null 时跳过类型校验（非必填场景）
            if (node == null || node.isNull()) {
                continue;
            }

            // 类型校验
            var typeError = validateType(name, prop.type(), node);
            if (typeError != null) {
                errors.add(typeError);
            }
        }

        return List.copyOf(errors);
    }

    /**
     * 校验属性值是否匹配声明的类型。
     *
     * @param name 属性名称
     * @param type 期望类型
     * @param node JSON 节点
     * @return 错误描述，null 表示通过
     */
    private String validateType(String name, PropertyType type, JsonNode node) {
        return switch (type) {
            case TEXT -> node.isTextual() ? null
                    : name + ": 类型不匹配，期望 TEXT";
            case NUMBER -> node.isNumber() ? null
                    : name + ": 类型不匹配，期望 NUMBER";
            case BOOLEAN -> node.isBoolean() ? null
                    : name + ": 类型不匹配，期望 BOOLEAN";
            case DATE -> validateDate(name, node);
            case DATETIME -> validateDatetime(name, node);
            case SELECT -> node.isTextual() ? null
                    : name + ": 类型不匹配，期望 SELECT";
            case MULTI_SELECT -> node.isArray() ? null
                    : name + ": 类型不匹配，期望 MULTI_SELECT";
            case URL -> validateUrl(name, node);
            case JSON -> (node.isObject() || node.isArray()) ? null
                    : name + ": 类型不匹配，期望 JSON";
        };
    }

    /**
     * 校验 ISO 8601 日期格式（yyyy-MM-dd）。
     */
    private String validateDate(String name, JsonNode node) {
        if (!node.isTextual()) {
            return name + ": 类型不匹配，期望 DATE";
        }
        try {
            LocalDate.parse(node.asText(), DateTimeFormatter.ISO_LOCAL_DATE);
            return null;
        } catch (DateTimeParseException e) {
            return name + ": 日期格式不合法，期望 ISO 8601 日期 (yyyy-MM-dd)";
        }
    }

    /**
     * 校验 ISO 8601 日期时间格式。
     */
    private String validateDatetime(String name, JsonNode node) {
        if (!node.isTextual()) {
            return name + ": 类型不匹配，期望 DATETIME";
        }
        try {
            DateTimeFormatter.ISO_DATE_TIME.parse(node.asText());
            return null;
        } catch (DateTimeParseException e) {
            return name + ": 日期时间格式不合法，期望 ISO 8601 日期时间";
        }
    }

    /**
     * 校验 URL 格式。
     */
    private String validateUrl(String name, JsonNode node) {
        if (!node.isTextual()) {
            return name + ": 类型不匹配，期望 URL";
        }
        try {
            new URI(node.asText()).toURL();
            return null;
        } catch (URISyntaxException | MalformedURLException | IllegalArgumentException e) {
            return name + ": URL 格式不合法";
        }
    }
}
