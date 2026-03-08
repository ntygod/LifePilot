package com.lifepilot.skill.validation;

import com.lifepilot.skill.markdown.MarkdownSkillParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 沙箱验证器 — 在隔离环境中验证 SKILL.md 定义的内部一致性。
 *
 * <p>校验规则：
 * <ul>
 *   <li>SKILL.md 内容可被 {@link MarkdownSkillParser} 成功解析</li>
 *   <li>instructions 长度不超过 5000 字符（沙箱安全限制）</li>
 *   <li>suggestedTools 数量不超过 10 个（沙箱安全限制）</li>
 * </ul></p>
 *
 * <p>使用临时文件进行解析验证，完成后清理临时文件。</p>
 *
 * @author zsg
 * @since 2026-02-25
 */
public class SandboxValidator {

    private static final Logger log = LoggerFactory.getLogger(SandboxValidator.class);

    /** 沙箱安全限制：instructions 最大长度。 */
    private static final int MAX_INSTRUCTIONS_LENGTH = 5000;

    /** 沙箱安全限制：suggestedTools 最大数量。 */
    private static final int MAX_TOOLS_COUNT = 10;

    private final MarkdownSkillParser markdownParser;

    public SandboxValidator(MarkdownSkillParser markdownParser) {
        this.markdownParser = markdownParser;
    }

    /**
     * 在沙箱中验证 SKILL.md 内容。
     *
     * <p>验证流程：
     * <ol>
     *   <li>使用 {@link MarkdownSkillParser} 解析 SKILL.md 内容</li>
     *   <li>校验 instructions 长度</li>
     *   <li>校验 suggestedTools 数量</li>
     *   <li>使用临时文件验证 SKILL.md 可被完整解析</li>
     * </ol></p>
     *
     * @param markdownContent SKILL.md 完整文本
     * @return 校验结果
     */
    public SandboxValidationResult validate(String markdownContent) {
        // 1. 解析 SKILL.md
        var parseResult = markdownParser.parse(markdownContent);
        if (!parseResult.success()) {
            log.debug("沙箱验证失败: SKILL.md 解析失败, errors={}", parseResult.errors());
            return new SandboxValidationResult(false, parseResult.errors());
        }

        var definition = parseResult.definition();
        if (definition == null) {
            // 理论上 success=true 时 definition 不为 null，防御性检查
            return new SandboxValidationResult(false, List.of("SKILL.md 解析成功但 SkillDefinition 为空"));
        }
        List<String> errors = new ArrayList<>();

        // 2. 校验 instructions 长度
        String instructions = definition.instructions();
        if (instructions.length() > MAX_INSTRUCTIONS_LENGTH) {
            errors.add("instructions 长度超过沙箱限制: " + instructions.length()
                    + " > " + MAX_INSTRUCTIONS_LENGTH);
        }

        // 3. 校验 suggestedTools 数量
        var suggestedTools = definition.suggestedTools();
        if (suggestedTools.size() > MAX_TOOLS_COUNT) {
            errors.add("suggestedTools 数量超过沙箱限制: " + suggestedTools.size()
                    + " > " + MAX_TOOLS_COUNT);
        }

        // 4. 使用临时文件验证 SKILL.md 可被完整解析
        verifyWithTempFile(markdownContent, errors);

        boolean passed = errors.isEmpty();
        if (passed) {
            log.debug("沙箱验证通过");
        } else {
            log.debug("沙箱验证失败: 错误数={}", errors.size());
        }
        return new SandboxValidationResult(passed, errors);
    }

    /**
     * 使用临时文件验证 SKILL.md 内容可被完整解析。
     *
     * <p>将 SKILL.md 内容写入临时文件，尝试读取并通过 {@link MarkdownSkillParser} 解析，
     * 验证文件 I/O 和解析的完整性。完成后清理临时文件。</p>
     *
     * @param markdownContent SKILL.md 完整文本
     * @param errors          错误收集列表
     */
    private void verifyWithTempFile(String markdownContent, List<String> errors) {
        Path tempFile = null;
        try {
            // 创建临时文件
            tempFile = Files.createTempFile("sandbox-skill-", ".md");
            Files.writeString(tempFile, markdownContent);

            // 从临时文件读取并解析，验证完整性
            String content = Files.readString(tempFile);
            var result = markdownParser.parse(content);
            if (!result.success()) {
                errors.add("临时文件解析验证失败: " + String.join("; ", result.errors()));
            }
        } catch (IOException e) {
            errors.add("临时文件操作失败: " + e.getMessage());
            log.debug("沙箱验证临时文件操作失败: {}", e.getMessage());
        } finally {
            // 清理临时文件
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException e) {
                    log.debug("清理沙箱临时文件失败: {}", e.getMessage());
                }
            }
        }
    }

    /**
     * 沙箱验证结果。
     *
     * @param passed 是否通过
     * @param errors 错误信息列表
     */
    public record SandboxValidationResult(boolean passed, List<String> errors) {
        /** 防御性拷贝。 */
        public SandboxValidationResult {
            errors = List.copyOf(errors);
        }
    }
}
