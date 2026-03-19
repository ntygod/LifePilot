package com.lifepilot.skill.generation;

import com.lifepilot.llm.LlmRequest;
import com.lifepilot.llm.LlmRouter;
import com.lifepilot.llm.LlmScene;
import com.lifepilot.prompt.PromptRegistry;
import com.lifepilot.skill.config.SkillConfigProperties;
import com.lifepilot.skill.markdown.MarkdownSkillParser;
import com.lifepilot.skill.markdown.MarkdownSkillSerializer;
import com.lifepilot.skill.model.*;
import com.lifepilot.skill.registry.SkillRegistry;
import com.lifepilot.skill.validation.SkillValidationPipeline;
import com.lifepilot.skill.validation.SkillValidationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Skill 生成器 — 使用 LLM 根据缺口描述生成 SKILL.md 格式的 Skill 定义。
 *
 * <p>生成流程：
 * <ol>
 *   <li>构建增强 Prompt：注入安全约束 + 工具能力清单 + 模板示例 + 已有 Skill 示例</li>
 *   <li>LLM 生成 SKILL.md 内容（YAML Frontmatter + Markdown Body）</li>
 *   <li>调用 {@link SkillValidationPipeline} 三重验证</li>
 *   <li>验证失败时构建修正 Prompt 进行迭代修正（最多 maxValidationIterations 次）</li>
 *   <li>验证通过后使用 {@link MarkdownSkillParser} 解析为待确认的 {@link SkillDefinition}</li>
 * </ol></p>
 *
 * @author zsg
 * @since 2026-02-25
 */
public class SkillGenerator {

    private static final Logger log = LoggerFactory.getLogger(SkillGenerator.class);

    /** 生成 Prompt 中包含的最大示例数。 */
    private static final int MAX_EXAMPLES = 3;

    private final LlmRouter llmRouter;
    private final SkillValidationPipeline validationPipeline;
    private final MarkdownSkillParser markdownParser;
    private final MarkdownSkillSerializer markdownSerializer;
    private final SkillRegistry skillRegistry;
    private final SkillConfigProperties config;
    private final PromptRegistry promptRegistry;
    private final ToolCapabilityManifest toolCapabilityManifest;
    private final SkillTemplateLibrary templateLibrary;
    private final Path autoSkillsDirectory;

    public SkillGenerator(LlmRouter llmRouter,
                          SkillValidationPipeline validationPipeline,
                          MarkdownSkillParser markdownParser,
                          MarkdownSkillSerializer markdownSerializer,
                          SkillRegistry skillRegistry,
                          SkillConfigProperties config,
                          PromptRegistry promptRegistry,
                          ToolCapabilityManifest toolCapabilityManifest,
                          SkillTemplateLibrary templateLibrary) {
        this.llmRouter = llmRouter;
        this.validationPipeline = validationPipeline;
        this.markdownParser = markdownParser;
        this.markdownSerializer = markdownSerializer;
        this.skillRegistry = skillRegistry;
        this.config = config;
        this.promptRegistry = promptRegistry;
        this.toolCapabilityManifest = toolCapabilityManifest;
        this.templateLibrary = templateLibrary;
        this.autoSkillsDirectory = Path.of(config.getDirectory(), "auto");
    }

    /**
     * 根据缺口描述生成 Skill。
     *
     * <p>流程：构建增强 Prompt → LLM 生成 SKILL.md → 提取 Markdown → 三重验证
     * → 验证失败时迭代修正（最多 maxValidationIterations 次）→ 解析为 SkillDefinition。</p>
     *
     * @param gap 缺口描述
     * @return 生成结果，包含待确认的 SkillDefinition 或失败原因
     */
    public GenerationResult generate(SkillGap gap) {
        int maxIterations = config.getAutoGeneration().getMaxValidationIterations();

        // 1. 构建工具能力清单（失败时降级为空字符串）
        String manifest = buildManifestSafely();

        // 2. 选择最匹配的模板
        SkillTemplate template = templateLibrary.findBestTemplate(gap);

        // 3. 构建增强 Prompt
        String prompt = buildEnhancedPrompt(gap, manifest, template);

        // 4. 调用 LLM 生成 SKILL.md
        String markdownContent;
        try {
            var response = llmRouter.call(LlmRequest.of(LlmScene.SKILL_GENERATION, prompt));
            markdownContent = extractMarkdown(response.content());
        } catch (Exception e) {
            log.error("LLM 调用失败，Skill 生成终止: gap={}, error={}", gap.suggestedId(), e.getMessage());
            return GenerationResult.error(e.getMessage());
        }

        // 5. 三重验证 + 迭代修正
        var validationResult = validationPipeline.validate(markdownContent);
        GenerationResult lastFailedResult = null;

        for (int i = 0; i < maxIterations && !validationResult.passed(); i++) {
            log.info("Skill 生成验证失败，尝试迭代修正: gap={}, iteration={}/{}, stage={}, errors={}",
                    gap.suggestedId(), i + 1, maxIterations,
                    validationResult.failedStage(), validationResult.errors());
            lastFailedResult = GenerationResult.validationFailed(validationResult);

            // 构建修正 Prompt
            String fixPrompt = buildFixPrompt(markdownContent, validationResult);

            // 调用 LLM 修正
            try {
                var fixResponse = llmRouter.call(LlmRequest.of(LlmScene.SKILL_GENERATION, fixPrompt));
                markdownContent = extractMarkdown(fixResponse.content());
                validationResult = validationPipeline.validate(markdownContent);
            } catch (Exception e) {
                log.warn("迭代修正 LLM 调用失败，返回上一次验证失败结果: gap={}, iteration={}, error={}",
                        gap.suggestedId(), i + 1, e.getMessage());
                return lastFailedResult;
            }
        }

        // 验证仍未通过
        if (!validationResult.passed()) {
            log.warn("Skill 生成验证失败（已用尽迭代次数）: gap={}, stage={}, errors={}",
                    gap.suggestedId(), validationResult.failedStage(), validationResult.errors());
            return GenerationResult.validationFailed(validationResult);
        }

        // 6. 解析为 SkillDefinition
        return parseAndBuild(gap, markdownContent);
    }

    /**
     * 持久化并注册自生成 Skill。
     *
     * <p>将 SKILL.md 文件写入 ~/.zhiwei/skills/auto/{skill-id}/SKILL.md，
     * 并注册到 SkillRegistry。调用此方法前，用户应已通过护栏确认
     * （generate_skill 工具为 HIGH 风险，护栏引擎会自动拦截并请求用户确认）。</p>
     *
     * @param definition 待持久化的 SkillDefinition
     * @return 注册是否成功
     */
    public boolean persistAndRegister(SkillDefinition definition) {
        Path skillFolder = autoSkillsDirectory.resolve(definition.id());
        try {
            Files.createDirectories(skillFolder);
        } catch (IOException e) {
            log.error("创建自生成 Skill 目录失败: path={}, error={}", skillFolder, e.getMessage());
            return false;
        }

        if (!(definition.source() instanceof SkillSource.AutoGenerated autoGen)) {
            log.warn("persistAndRegister 仅支持 AutoGenerated 来源: skillId={}", definition.id());
            return false;
        }
        var confirmedSource = new SkillSource.AutoGenerated(
                autoGen.generatorTraceId(),
                autoGen.generatedAt(),
                autoGen.triggerRequest(),
                true
        );
        var confirmedDefinition = definition.toBuilder()
                .source(confirmedSource)
                .build();

        String markdownContent = markdownSerializer.serialize(confirmedDefinition);
        Path targetFile = skillFolder.resolve("SKILL.md");
        try {
            Files.writeString(targetFile, markdownContent);
            log.info("自生成 Skill 已持久化: skillId={}, path={}", definition.id(), targetFile);
        } catch (IOException e) {
            log.error("自生成 Skill 持久化失败: skillId={}, path={}, error={}",
                    definition.id(), targetFile, e.getMessage());
            return false;
        }

        boolean registered = skillRegistry.register(confirmedDefinition);
        if (registered) {
            log.info("自生成 Skill 持久化并注册成功: skillId={}", definition.id());
        } else {
            log.warn("自生成 Skill 注册失败: skillId={}", definition.id());
        }
        return registered;
    }

    /**
     * 用户拒绝自生成 Skill。
     *
     * @param definition 被拒绝的 SkillDefinition
     */
    public void reject(SkillDefinition definition) {
        log.info("用户拒绝自生成 Skill: skillId={}", definition.id());
    }

    // ==================== Prompt 构建 ====================

    /**
     * 构建增强生成 Prompt — 注入工具能力清单和模板示例。
     *
     * @param gap      缺口描述
     * @param manifest 工具能力清单文本
     * @param template 匹配的 Skill 模板
     * @return 完整的增强生成 Prompt
     */
    String buildEnhancedPrompt(SkillGap gap, String manifest, SkillTemplate template) {
        var validation = config.getValidation();

        var gapDesc = "- 建议 ID: " + gap.suggestedId() + "\n" +
                "- 建议名称: " + gap.suggestedName() + "\n" +
                "- 触发请求: " + gap.triggerRequest() + "\n" +
                "- 建议工具: " + gap.suggestedTools() + "\n" +
                "- 分析原因: " + gap.reason();

        var summaries = skillRegistry.listSummaries();
        var existingSkillsText = "";
        if (!summaries.isEmpty()) {
            var sb = new StringBuilder();
            summaries.stream()
                    .limit(MAX_EXAMPLES)
                    .forEach(s -> sb.append("- ").append(s).append("\n"));
            existingSkillsText = sb.toString();
        }

        return promptRegistry.render("generation/skill-generation-enhanced", Map.of(
                "maxSteps", String.valueOf(validation.getAutoGeneratedMaxSteps()),
                "maxTimeout", String.valueOf(validation.getAutoGeneratedMaxTimeout()),
                "maxTokens", String.valueOf(validation.getAutoGeneratedMaxTokens()),
                "maxCostCents", String.valueOf(config.getAutoGeneration().getMaxCostCents()),
                "gapDescription", gapDesc,
                "existingSkills", existingSkillsText,
                "toolManifest", manifest,
                "templateExample", template.markdownContent()
        ));
    }

    /**
     * 构建修正 Prompt — 包含失败内容和验证错误信息。
     *
     * @param failedContent    验证失败的 SKILL.md 内容
     * @param validationResult 验证结果
     * @return 修正 Prompt
     */
    String buildFixPrompt(String failedContent, SkillValidationResult validationResult) {
        var errorsText = String.join("\n", validationResult.errors());
        return promptRegistry.render("generation/skill-fix", Map.of(
                "failedContent", failedContent,
                "errors", errorsText
        ));
    }

    /**
     * 构建旧版生成 Prompt（保留兼容性）。
     *
     * @param gap 缺口描述
     * @return 完整的生成 Prompt
     */
    String buildGenerationPrompt(SkillGap gap) {
        var validation = config.getValidation();

        var gapDesc = "- 建议 ID: " + gap.suggestedId() + "\n" +
                "- 建议名称: " + gap.suggestedName() + "\n" +
                "- 触发请求: " + gap.triggerRequest() + "\n" +
                "- 建议工具: " + gap.suggestedTools() + "\n" +
                "- 分析原因: " + gap.reason();

        var summaries = skillRegistry.listSummaries();
        var existingSkillsText = "";
        if (!summaries.isEmpty()) {
            var sb = new StringBuilder();
            summaries.stream()
                    .limit(MAX_EXAMPLES)
                    .forEach(s -> sb.append("- ").append(s).append("\n"));
            existingSkillsText = sb.toString();
        }

        return promptRegistry.render("generation/skill-generation", Map.of(
                "maxSteps", String.valueOf(validation.getAutoGeneratedMaxSteps()),
                "maxTimeout", String.valueOf(validation.getAutoGeneratedMaxTimeout()),
                "maxTokens", String.valueOf(validation.getAutoGeneratedMaxTokens()),
                "maxCostCents", String.valueOf(config.getAutoGeneration().getMaxCostCents()),
                "gapDescription", gapDesc,
                "existingSkills", existingSkillsText
        ));
    }

    // ==================== 内部辅助方法 ====================

    /**
     * 安全构建工具能力清单，失败时降级为空字符串。
     *
     * @return 工具能力清单文本，构建失败时返回空字符串
     */
    private String buildManifestSafely() {
        try {
            return toolCapabilityManifest.buildManifest();
        } catch (Exception e) {
            log.warn("工具能力清单构建失败，降级为空: error={}", e.getMessage());
            return "";
        }
    }

    /**
     * 解析 Markdown 内容为 SkillDefinition 并设置 AutoGenerated 来源。
     *
     * @param gap             缺口描述
     * @param markdownContent 验证通过的 SKILL.md 内容
     * @return 生成结果
     */
    private GenerationResult parseAndBuild(SkillGap gap, String markdownContent) {
        var parseResult = markdownParser.parse(markdownContent);
        if (!parseResult.success() || parseResult.definition() == null) {
            log.warn("自生成 Skill SKILL.md 解析为 SkillDefinition 失败: gap={}", gap.suggestedId());
            return GenerationResult.error("SKILL.md 解析为 SkillDefinition 失败");
        }

        var autoSource = new SkillSource.AutoGenerated(
                UUID.randomUUID().toString(),
                Instant.now(),
                gap.triggerRequest(),
                false
        );
        var definition = parseResult.definition().toBuilder().source(autoSource).build();

        log.info("Skill 生成成功: skillId={}, gap={}", definition.id(), gap.suggestedId());
        return GenerationResult.success(definition, markdownContent);
    }

    /**
     * 从 LLM 响应中提取 SKILL.md 内容。
     *
     * <p>处理 LLM 可能返回的代码块包裹格式（```markdown ... ```、```yaml ... ```、``` ... ```）。</p>
     *
     * @param content LLM 原始响应内容
     * @return 提取后的 SKILL.md 字符串
     */
    static String extractMarkdown(String content) {
        String trimmed = content.trim();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            int lastBacktick = trimmed.lastIndexOf("```");
            if (firstNewline > 0 && lastBacktick > firstNewline) {
                return trimmed.substring(firstNewline + 1, lastBacktick).trim();
            }
        }
        return trimmed;
    }

    /**
     * 生成结果 — 封装生成成功/失败的完整信息。
     *
     * @param success          是否成功
     * @param definition       生成的 SkillDefinition（成功时非 null）
     * @param markdownContent  生成的 SKILL.md 内容（成功时非 null）
     * @param validationResult 验证结果（验证失败时非 null）
     * @param errorMessage     错误信息（失败时非 null）
     * @author zsg
     * @since 2026-02-25
     */
    public record GenerationResult(
            boolean success,
            @Nullable SkillDefinition definition,
            @Nullable String markdownContent,
            @Nullable SkillValidationResult validationResult,
            @Nullable String errorMessage
    ) {
        /**
         * 创建生成成功结果。
         *
         * @param definition      生成的 SkillDefinition
         * @param markdownContent 生成的 SKILL.md 内容
         * @return 成功结果
         */
        public static GenerationResult success(SkillDefinition definition, String markdownContent) {
            return new GenerationResult(true, definition, markdownContent, null, null);
        }

        /**
         * 创建验证失败结果。
         *
         * @param result 验证结果
         * @return 验证失败结果
         */
        public static GenerationResult validationFailed(SkillValidationResult result) {
            return new GenerationResult(false, null, null, result, "三重验证失败");
        }

        /**
         * 创建生成错误结果。
         *
         * @param message 错误信息
         * @return 错误结果
         */
        public static GenerationResult error(String message) {
            return new GenerationResult(false, null, null, null, message);
        }
    }
}
