package com.lifepilot.meta.infra.browser;

/**
 * DOM 中一个可交互元素的标号信息。
 *
 * @param index     连续整数，供 LLM 选择操作目标
 * @param tag       HTML 标签小写（a/button/input 等）
 * @param role      ARIA role（可空字符串）
 * @param text      可见文本或 value/placeholder，截断到 80 字符
 * @param name      name 属性
 * @param id        id 属性
 * @param ariaLabel aria-label 属性
 * @param bbox      [x, y, width, height]，整数像素，viewport 坐标系
 *
 * @author zsg
 * @since 2026-04-24
 */
public record IndexedElement(
        int index,
        String tag,
        String role,
        String text,
        String name,
        String id,
        String ariaLabel,
        int[] bbox
) {}
