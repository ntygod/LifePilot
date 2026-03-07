package com.lifepilot.skill.validation;

import com.lifepilot.skill.markdown.MarkdownSkillParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import java.util.List;
import java.util.Map;

/**
 * 格式验证器 — 校验 SKILL.md 格式（YAML Frontmatter + Markdown Body）。
 *
 * <p>委托 {@link MarkdownSkillParser} 完成解析与校验，验证流程：
 * <ol>
 *   <li>校验输入非空</li>
 *   <li>调用 MarkdownSkillParser.parse() 解析 SKILL.md 内容</li>
 *   <li>解析成功时将 frontmatterMap 包装为 {@code {"skill": frontmatterMap}} 格式以兼容 SecurityValidator</li>
 * </ol></p>
 *
 * @author zsg
 * @since 2026-02-25
 */
public class FormatValidator {

    private static final Logger log = LoggerFactory.getLogger(FormatValidator.class);

    private final MarkdownSkillParser markdownParser;

    public FormatValidator(MarkdownSkillParser markdownParser) {
        this.markdownParser = markdownParser;
    }

    /**
     * 校验 SKILL.md 格式内容。
     *
     * <p>委托 {@link MarkdownSkillParser} 解析 YAML Frontmatter 和 Markdown Body，
     * 成功时将 frontmatterMap 包装为 {@code {"skill": frontmatterMap}} 以兼容下游 SecurityValidator。</p>
     *
     * @param markdownContent SKILL.md 完整文本
     * @return 校验结果，包含解析后的 Map（成功时）
     */
    public FormatValidationResult validate(String markdownContent) {
        // 1. 校验输入非空
        if (markdownContent == null || markdownContent.isBlank()) {
            log.debug("格式验证失败: SKILL.md 内容为空");
            return new FormatValidationResult(false, List.of("SKILL.md 内容不能为空"), null);
        }

        // 2. 调用 MarkdownSkillParser 解析
        var parseResult = markdownParser.parse(markdownContent);

        if (!parseResult.success()) {
            log.debug("格式验证失败: 解析错误数={}", parseResult.errors().size());
            return new FormatValidationResult(false, parseResult.errors(), null);
        }

        // 3. 包装 frontmatterMap 为 {"skill": frontmatterMap} 以兼容 SecurityValidator
        var wrappedMap = Map.of("skill", (Object) parseResult.frontmatterMap());
        log.debug("格式验证通过");
        return new FormatValidationResult(true, List.of(), wrappedMap);
    }

    /**
     * 格式验证结果。
     *
     * @param passed    是否通过
     * @param errors    错误信息列表
     * @param parsedMap 解析后的 Map（通过时非 null）
     */
    public record FormatValidationResult(
            boolean passed,
            List<String> errors,
            @Nullable Map<String, Object> parsedMap
    ) {
        /** 防御性拷贝。 */
        public FormatValidationResult {
            errors = List.copyOf(errors);
        }
    }
}
