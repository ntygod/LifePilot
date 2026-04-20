package com.lifepilot.document.generator;

import org.springframework.lang.Nullable;

import java.util.List;

/**
 * PowerPoint 幻灯片中间模型。
 *
 * <p>Tool 层从 ToolInput 反序列化为 SlideData 列表传给 PowerpointGenerator,
 * 内部结构 LLM 可见（工具 schema 直接描述此 record 字段）。</p>
 *
 * <p>Compact constructor 对 bullets 用 {@link List#copyOf} 做不可变拷贝
 * （null 时用 {@link List#of}；List.copyOf 会在遇到 null 元素时抛 NPE,
 * 保护要点列表不变性）。title 和 notes 可为 null / 空字符串,生成器按需渲染。</p>
 *
 * @param title   标题（可为 null / 空字符串 → 不渲染标题框）
 * @param bullets 要点列表（每项一行;null 归一化为空列表;不允许含 null 元素）
 * @param notes   备注文本（可为 null / 空字符串 → 不写入备注区）
 * @author zsg
 * @since 2026-04-20
 */
public record SlideData(
        @Nullable String title,
        List<String> bullets,
        @Nullable String notes
) {

    /**
     * 防御性拷贝：外层不可变,null 归一化为空列表;不允许含 null 元素。
     */
    public SlideData {
        if (bullets == null) {
            bullets = List.of();
        } else {
            // List.copyOf 会在遇到 null 元素时抛 NPE —— 要点必须有值
            bullets = List.copyOf(bullets);
        }
    }

    /** 简化构造：仅标题 + 要点。 */
    public static SlideData of(String title, List<String> bullets) {
        return new SlideData(title, bullets, null);
    }

    /** 简化构造：标题 + 要点 + 备注。 */
    public static SlideData withNotes(String title, List<String> bullets, String notes) {
        return new SlideData(title, bullets, notes);
    }
}
