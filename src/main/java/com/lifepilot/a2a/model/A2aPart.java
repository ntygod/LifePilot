package com.lifepilot.a2a.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import org.springframework.lang.Nullable;

import java.util.Map;

/**
 * A2A Part — 消息内容片段。
 *
 * <p>使用 sealed interface + {@code @JsonTypeInfo} 实现多态 JSON 序列化。
 * type 字段区分 "text" / "file" / "data" 三种子类型。</p>
 *
 * @author zsg
 * @since 2026-02-28
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = A2aPart.Text.class, name = "text"),
        @JsonSubTypes.Type(value = A2aPart.File.class, name = "file"),
        @JsonSubTypes.Type(value = A2aPart.Data.class, name = "data")
})
public sealed interface A2aPart permits A2aPart.Text, A2aPart.File, A2aPart.Data {

    /** 文本内容片段。 */
    record Text(
            String text,
            @Nullable @JsonInclude(JsonInclude.Include.NON_NULL) Map<String, Object> metadata
    ) implements A2aPart {}

    /** 文件内容片段。 */
    record File(
            A2aFileContent file,
            @Nullable @JsonInclude(JsonInclude.Include.NON_NULL) Map<String, Object> metadata
    ) implements A2aPart {}

    /** 结构化数据片段。 */
    record Data(
            Map<String, Object> data,
            @Nullable @JsonInclude(JsonInclude.Include.NON_NULL) Map<String, Object> metadata
    ) implements A2aPart {}
}
