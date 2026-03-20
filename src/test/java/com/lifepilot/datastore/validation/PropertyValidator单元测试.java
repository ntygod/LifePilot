package com.lifepilot.datastore.validation;

import com.lifepilot.datastore.model.PropertyDefinition;
import com.lifepilot.datastore.model.PropertyType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PropertyValidator 单元测试 — 校验文档 JSON 是否符合属性定义。
 *
 * @author zsg
 * @since 2026-03-20
 */
class PropertyValidator单元测试 {

    private final PropertyValidator validator = new PropertyValidator();

    // ---- 基础场景 ----

    @Test
    void 空属性定义_跳过校验() {
        var errors = validator.validate(null, """
                {"anything": "goes"}""");
        assertThat(errors).isEmpty();
    }

    @Test
    void 空属性列表_跳过校验() {
        var errors = validator.validate(List.of(), """
                {"anything": "goes"}""");
        assertThat(errors).isEmpty();
    }

    @Test
    void 无效JSON_返回错误() {
        var props = List.of(new PropertyDefinition("name", PropertyType.TEXT, true, null));
        var errors = validator.validate(props, "not-json");
        assertThat(errors).hasSize(1);
        assertThat(errors.getFirst()).contains("无效的 JSON");
    }

    @Test
    void 非对象JSON_返回错误() {
        var props = List.of(new PropertyDefinition("name", PropertyType.TEXT, true, null));
        var errors = validator.validate(props, "[1,2,3]");
        assertThat(errors).hasSize(1);
        assertThat(errors.getFirst()).contains("JSON 对象");
    }

    // ---- 必填校验 ----

    @Test
    void 必填属性缺失_返回错误() {
        var props = List.of(new PropertyDefinition("title", PropertyType.TEXT, true, null));
        var errors = validator.validate(props, """
                {"other": "value"}""");
        assertThat(errors).hasSize(1);
        assertThat(errors.getFirst()).contains("title").contains("缺少必需属性");
    }

    @Test
    void 必填属性为null_返回错误() {
        var props = List.of(new PropertyDefinition("title", PropertyType.TEXT, true, null));
        var errors = validator.validate(props, """
                {"title": null}""");
        assertThat(errors).hasSize(1);
        assertThat(errors.getFirst()).contains("缺少必需属性");
    }

    @Test
    void 非必填属性缺失_通过() {
        var props = List.of(new PropertyDefinition("title", PropertyType.TEXT, false, null));
        var errors = validator.validate(props, """
                {"other": "value"}""");
        assertThat(errors).isEmpty();
    }

    // ---- 类型校验 ----

    @Test
    void TEXT类型_字符串值_通过() {
        var props = List.of(new PropertyDefinition("name", PropertyType.TEXT, true, null));
        var errors = validator.validate(props, """
                {"name": "hello"}""");
        assertThat(errors).isEmpty();
    }

    @Test
    void TEXT类型_非字符串值_失败() {
        var props = List.of(new PropertyDefinition("name", PropertyType.TEXT, true, null));
        var errors = validator.validate(props, """
                {"name": 123}""");
        assertThat(errors).hasSize(1);
        assertThat(errors.getFirst()).contains("TEXT");
    }

    @Test
    void NUMBER类型_数值_通过() {
        var props = List.of(new PropertyDefinition("score", PropertyType.NUMBER, true, null));
        var errors = validator.validate(props, """
                {"score": 99.5}""");
        assertThat(errors).isEmpty();
    }

    @Test
    void NUMBER类型_整数_通过() {
        var props = List.of(new PropertyDefinition("count", PropertyType.NUMBER, true, null));
        var errors = validator.validate(props, """
                {"count": 42}""");
        assertThat(errors).isEmpty();
    }

    @Test
    void NUMBER类型_字符串值_失败() {
        var props = List.of(new PropertyDefinition("score", PropertyType.NUMBER, true, null));
        var errors = validator.validate(props, """
                {"score": "high"}""");
        assertThat(errors).hasSize(1);
        assertThat(errors.getFirst()).contains("NUMBER");
    }

    @Test
    void BOOLEAN类型_布尔值_通过() {
        var props = List.of(new PropertyDefinition("active", PropertyType.BOOLEAN, true, null));
        var errors = validator.validate(props, """
                {"active": true}""");
        assertThat(errors).isEmpty();
    }

    @Test
    void BOOLEAN类型_非布尔值_失败() {
        var props = List.of(new PropertyDefinition("active", PropertyType.BOOLEAN, true, null));
        var errors = validator.validate(props, """
                {"active": "yes"}""");
        assertThat(errors).hasSize(1);
        assertThat(errors.getFirst()).contains("BOOLEAN");
    }

    @Test
    void DATE类型_合法日期_通过() {
        var props = List.of(new PropertyDefinition("birthday", PropertyType.DATE, true, null));
        var errors = validator.validate(props, """
                {"birthday": "2026-03-20"}""");
        assertThat(errors).isEmpty();
    }

    @Test
    void DATE类型_非法日期_失败() {
        var props = List.of(new PropertyDefinition("birthday", PropertyType.DATE, true, null));
        var errors = validator.validate(props, """
                {"birthday": "not-a-date"}""");
        assertThat(errors).hasSize(1);
        assertThat(errors.getFirst()).contains("日期格式不合法");
    }

    @Test
    void DATETIME类型_合法日期时间_通过() {
        var props = List.of(new PropertyDefinition("ts", PropertyType.DATETIME, true, null));
        var errors = validator.validate(props, """
                {"ts": "2026-03-20T10:30:00"}""");
        assertThat(errors).isEmpty();
    }

    @Test
    void DATETIME类型_非法格式_失败() {
        var props = List.of(new PropertyDefinition("ts", PropertyType.DATETIME, true, null));
        var errors = validator.validate(props, """
                {"ts": "2026/03/20"}""");
        assertThat(errors).hasSize(1);
        assertThat(errors.getFirst()).contains("日期时间格式不合法");
    }

    @Test
    void SELECT类型_字符串值_通过() {
        var props = List.of(new PropertyDefinition("status", PropertyType.SELECT, true, null));
        var errors = validator.validate(props, """
                {"status": "active"}""");
        assertThat(errors).isEmpty();
    }

    @Test
    void MULTI_SELECT类型_数组值_通过() {
        var props = List.of(new PropertyDefinition("tags", PropertyType.MULTI_SELECT, true, null));
        var errors = validator.validate(props, """
                {"tags": ["java", "spring"]}""");
        assertThat(errors).isEmpty();
    }

    @Test
    void MULTI_SELECT类型_非数组值_失败() {
        var props = List.of(new PropertyDefinition("tags", PropertyType.MULTI_SELECT, true, null));
        var errors = validator.validate(props, """
                {"tags": "java"}""");
        assertThat(errors).hasSize(1);
        assertThat(errors.getFirst()).contains("MULTI_SELECT");
    }

    @Test
    void URL类型_合法URL_通过() {
        var props = List.of(new PropertyDefinition("link", PropertyType.URL, true, null));
        var errors = validator.validate(props, """
                {"link": "https://example.com/path"}""");
        assertThat(errors).isEmpty();
    }

    @Test
    void URL类型_非法URL_失败() {
        var props = List.of(new PropertyDefinition("link", PropertyType.URL, true, null));
        var errors = validator.validate(props, """
                {"link": "not a url"}""");
        assertThat(errors).hasSize(1);
        assertThat(errors.getFirst()).contains("URL 格式不合法");
    }

    @Test
    void JSON类型_对象值_通过() {
        var props = List.of(new PropertyDefinition("meta", PropertyType.JSON, true, null));
        var errors = validator.validate(props, """
                {"meta": {"key": "value"}}""");
        assertThat(errors).isEmpty();
    }

    @Test
    void JSON类型_数组值_通过() {
        var props = List.of(new PropertyDefinition("items", PropertyType.JSON, true, null));
        var errors = validator.validate(props, """
                {"items": [1, 2, 3]}""");
        assertThat(errors).isEmpty();
    }

    @Test
    void JSON类型_标量值_失败() {
        var props = List.of(new PropertyDefinition("meta", PropertyType.JSON, true, null));
        var errors = validator.validate(props, """
                {"meta": "string"}""");
        assertThat(errors).hasSize(1);
        assertThat(errors.getFirst()).contains("JSON");
    }

    // ---- 多属性组合 ----

    @Test
    void 多属性_部分失败_返回所有错误() {
        var props = List.of(
                new PropertyDefinition("name", PropertyType.TEXT, true, null),
                new PropertyDefinition("age", PropertyType.NUMBER, true, null),
                new PropertyDefinition("active", PropertyType.BOOLEAN, false, null)
        );
        var errors = validator.validate(props, """
                {"name": 123, "age": "not-number", "active": true}""");
        assertThat(errors).hasSize(2);
    }

    @Test
    void 多属性_全部通过() {
        var props = List.of(
                new PropertyDefinition("name", PropertyType.TEXT, true, null),
                new PropertyDefinition("score", PropertyType.NUMBER, true, null),
                new PropertyDefinition("done", PropertyType.BOOLEAN, false, null)
        );
        var errors = validator.validate(props, """
                {"name": "test", "score": 95, "done": false}""");
        assertThat(errors).isEmpty();
    }
}
