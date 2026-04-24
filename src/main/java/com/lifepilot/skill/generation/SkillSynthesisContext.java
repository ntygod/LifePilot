package com.lifepilot.skill.generation;

import java.util.List;

/**
 * SkillSynthesizer 的输入上下文。
 *
 * <p>承载"为什么要生成""目标场景""可用工具清单"三要素，由
 * {@link SkillSynthesizer} 用于渲染 prompt 并对 LLM 产物做严格校验。
 * 三个字段构成完全不可变视图：{@code availableToolIds} 通过
 * {@link List#copyOf(java.util.Collection)} 做防御式拷贝。</p>
 *
 * @param gapDescription    为什么要生成（需求描述）
 * @param targetScenario    目标场景（LLM 要对准此场景写 description）
 * @param availableToolIds  可引用的工具 id 列表；为 null 时视为空
 *
 * @author zsg
 * @since 2026-04-24
 */
public record SkillSynthesisContext(
        String gapDescription,
        String targetScenario,
        List<String> availableToolIds
) {
    public SkillSynthesisContext {
        if (gapDescription == null || gapDescription.isBlank()) {
            throw new IllegalArgumentException("gapDescription 不能为空");
        }
        if (targetScenario == null || targetScenario.isBlank()) {
            throw new IllegalArgumentException("targetScenario 不能为空");
        }
        availableToolIds = availableToolIds == null ? List.of() : List.copyOf(availableToolIds);
    }
}
