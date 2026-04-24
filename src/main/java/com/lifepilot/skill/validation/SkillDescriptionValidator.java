package com.lifepilot.skill.validation;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 对 SKILL.md 的 description 做硬约束校验。
 *
 * <p>规则（见 docs/skill-spec.md §2.1 和 §5）：</p>
 * <ol>
 *   <li>≤1024 字符</li>
 *   <li>以 "当..." / "用于..." / "Use when..." / "Use this when..." 开头</li>
 *   <li>不得包含工作流词（步骤 N / 首先 / 然后 / 接下来 / Step N / First / Then）</li>
 * </ol>
 *
 * @author zsg
 * @since 2026-04-24
 */
@Component
public class SkillDescriptionValidator {

    private static final int MAX = 1024;
    private static final Pattern START = Pattern.compile(
            "^\\s*(当|用于|Use when|Use this when)", Pattern.CASE_INSENSITIVE);
    private static final List<Pattern> WORKFLOW_WORDS = List.of(
            Pattern.compile("步骤\\s*[0-9]"),
            Pattern.compile("首先"),
            Pattern.compile("然后"),
            Pattern.compile("接下来"),
            Pattern.compile("\\bStep\\s*[0-9]", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bFirst\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bThen\\b", Pattern.CASE_INSENSITIVE)
    );

    /**
     * 校验 description 是否满足硬约束。
     *
     * @param description SKILL.md frontmatter 中的 description 字段
     * @throws IllegalArgumentException 校验不通过时抛出，消息内包含规范定位
     */
    public void validate(String description) {
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("description 不能为空");
        }
        if (description.length() > MAX) {
            throw new IllegalArgumentException(
                    "description 长度超过 ≤" + MAX + " 字符限制（当前 " + description.length() + "）");
        }
        if (!START.matcher(description).find()) {
            throw new IllegalArgumentException(
                    "description 必须以 '当...' / '用于...' / 'Use when...' 开头（见 docs/skill-spec.md §2.1）");
        }
        for (Pattern p : WORKFLOW_WORDS) {
            if (p.matcher(description).find()) {
                throw new IllegalArgumentException(
                        "description 不得含工作流词（步骤/首先/然后/Step N/First/Then），工作流应写在 body。命中: "
                        + p.pattern());
            }
        }
    }
}
