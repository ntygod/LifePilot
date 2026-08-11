package com.lifepilot.skill.validation;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * SKILL.md body 小节结构与长度校验。
 *
 * <p>规则（见 docs/skill-spec.md §2.3 和 §5）：</p>
 * <ol>
 *   <li>≤5000 字符（超长请拆 references/）</li>
 *   <li>必需 4 小节：## 触发判断 / ## 决策路径 / ## 输出标准 / ## 失败策略</li>
 * </ol>
 *
 * @author zsg
 * @since 2026-04-24
 */
@Component
public class SkillBodyValidator {

    private static final int MAX = 5000;
    private static final List<String> REQUIRED_HEADINGS = List.of(
            "## 触发判断", "## 决策路径", "## 输出标准", "## 失败策略");

    /**
     * 校验 body 是否满足小节结构与字数硬限。
     *
     * @param body SKILL.md 去掉 frontmatter 之后的正文
     * @throws IllegalArgumentException 校验不通过时抛出，消息内包含规范定位
     */
    public void validate(String body) {
        if (body == null || body.isBlank()) {
            throw new IllegalArgumentException("body 不能为空");
        }
        if (body.length() > MAX) {
            throw new IllegalArgumentException(
                    "body 长度超过 ≤" + MAX + " 字符限制（当前 " + body.length()
                    + "），请拆分详细内容到 references/ 子目录");
        }
        for (String h : REQUIRED_HEADINGS) {
            if (!body.contains(h)) {
                throw new IllegalArgumentException(
                        "body 缺少必需小节: " + h + "（见 docs/skill-spec.md §2.3）");
            }
        }
    }
}
