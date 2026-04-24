package com.lifepilot.skill.generation;

import com.lifepilot.generation.router.GenerationRouter;
import com.lifepilot.llm.LlmResponse;
import com.lifepilot.llm.LlmScene;
import com.lifepilot.modelservice.model.GenerationCapability;
import com.lifepilot.prompt.PromptRegistry;
import com.lifepilot.skill.MarkdownSkillParser;
import com.lifepilot.skill.MarkdownSkillParser.ParsedSkill;
import com.lifepilot.skill.config.SkillConfigProperties;
import com.lifepilot.skill.event.SkillGeneratedEvent;
import com.lifepilot.skill.install.SkillInstallation;
import com.lifepilot.skill.install.SkillInstaller;
import com.lifepilot.skill.install.SkillSourceType;
import com.lifepilot.skill.validation.SkillValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Map;

/**
 * Skill 自生成管线。
 *
 * <p>四步闭环（见 docs/skill-spec.md §6 自生成路径）：</p>
 * <ol>
 *   <li><b>首次生成</b>：渲染 {@code generation/skill-synthesis} 模板 → 调 LLM → 得到 SKILL.md 原文</li>
 *   <li><b>校验</b>：{@link MarkdownSkillParser#parse(String)} + {@link SkillValidator#validateGenerated(ParsedSkill)}</li>
 *   <li><b>迭代修正</b>：失败时渲染 {@code generation/skill-fix} 模板（带上次产物 + 错误信息）→ 重新生成；最多
 *       重试 {@value #MAX_FIX_ATTEMPTS} 次（即"首次 + MAX_FIX_ATTEMPTS 次修正"）</li>
 *   <li><b>落库</b>：通过 {@link SkillInstaller#install(SkillInstaller.InstallRequest)} 统一流水线写入
 *       {@code {skillDir}/auto/<name>/SKILL.md}，{@code source_type = AUTO_GENERATED}；
 *       随后发布 {@link SkillGeneratedEvent} 供 SSE / 审计监听器消费</li>
 * </ol>
 *
 * <p>安全约束（由 {@link SkillValidator#validateGenerated(ParsedSkill)} 保证）：
 * AUTO_GENERATED 路径禁止引用未知工具，禁止声明 HIGH/CRITICAL 风险工具。</p>
 *
 * <p>prompt 模板 {@code generation/skill-synthesis} 由 C.4 负责补齐；
 * 本 task 不写模板文件。若运行期模板缺失，{@link PromptRegistry#render} 会抛出
 * {@link com.lifepilot.prompt.PromptTemplateNotFoundException} 被外层捕获。</p>
 *
 * @author zsg
 * @since 2026-04-24
 */
@Service
public class SkillSynthesizer {

    private static final Logger log = LoggerFactory.getLogger(SkillSynthesizer.class);

    /** 首次生成失败后允许的修正轮数（总尝试次数为 1 + MAX_FIX_ATTEMPTS）。 */
    private static final int MAX_FIX_ATTEMPTS = 2;

    /** 自生成 SKILL.md 相对子目录（相对于 {@link SkillConfigProperties#getDirectory()}）。 */
    private static final String AUTO_SUBDIR = "auto";

    private static final String SYNTHESIS_PROMPT_KEY = "generation/skill-synthesis";
    private static final String FIX_PROMPT_KEY = "generation/skill-fix";

    private final GenerationRouter generationRouter;
    private final PromptRegistry promptRegistry;
    private final MarkdownSkillParser parser;
    private final SkillValidator validator;
    private final SkillInstaller installer;
    private final ApplicationEventPublisher publisher;
    private final SkillConfigProperties config;

    public SkillSynthesizer(GenerationRouter generationRouter,
                            PromptRegistry promptRegistry,
                            MarkdownSkillParser parser,
                            SkillValidator validator,
                            SkillInstaller installer,
                            ApplicationEventPublisher publisher,
                            SkillConfigProperties config) {
        this.generationRouter = generationRouter;
        this.promptRegistry = promptRegistry;
        this.parser = parser;
        this.validator = validator;
        this.installer = installer;
        this.publisher = publisher;
        this.config = config;
    }

    /**
     * 执行一轮完整自生成：LLM → 校验（允许迭代修正）→ 落库 → 发事件。
     *
     * @param ctx 输入上下文（为什么生成 + 目标场景 + 可用工具）
     * @return 最终落库的 {@link SkillInstallation}
     * @throws SkillSynthesisException 全部尝试耗尽仍未产出合规 SKILL.md
     */
    public SkillInstallation synthesize(SkillSynthesisContext ctx) {
        String generated = firstGenerate(ctx);
        Exception lastFailure = null;

        for (int attempt = 0; attempt <= MAX_FIX_ATTEMPTS; attempt++) {
            try {
                ParsedSkill parsed = parser.parse(generated);
                validator.validateGenerated(parsed);

                SkillInstallation install = installer.install(new SkillInstaller.InstallRequest(
                        SkillSourceType.AUTO_GENERATED,
                        "ai-generated",
                        null,
                        generated,
                        autoDir()));

                publisher.publishEvent(new SkillGeneratedEvent(
                        install.name(), install.sourceType(), Instant.now()));

                log.info("SkillSynthesizer 生成成功: name={}, attempts={}",
                        install.name(), attempt + 1);
                return install;

            } catch (Exception e) {
                lastFailure = e;
                log.warn("SkillSynthesizer 尝试 {}/{} 失败: error={}, message={}",
                        attempt + 1, MAX_FIX_ATTEMPTS + 1,
                        e.getClass().getSimpleName(), e.getMessage());
                if (attempt == MAX_FIX_ATTEMPTS) {
                    break;
                }
                try {
                    generated = fix(generated, e.getMessage(), ctx);
                } catch (Exception fixException) {
                    lastFailure = fixException;
                    log.warn("SkillSynthesizer 修正 prompt 调用失败: error={}, message={}",
                            fixException.getClass().getSimpleName(), fixException.getMessage());
                    break;
                }
            }
        }
        throw new SkillSynthesisException(
                "SkillSynthesizer 失败（已尝试 " + (MAX_FIX_ATTEMPTS + 1) + " 次）",
                lastFailure);
    }

    /** 渲染首次生成 prompt 并调 LLM。 */
    private String firstGenerate(SkillSynthesisContext ctx) {
        String prompt = promptRegistry.render(SYNTHESIS_PROMPT_KEY, Map.of(
                "gapDescription", ctx.gapDescription(),
                "targetScenario", ctx.targetScenario(),
                "availableToolIds", String.join(", ", ctx.availableToolIds())
        ));
        return callLlm(prompt);
    }

    /** 渲染修正 prompt（带上次产物 + 错误信息）并调 LLM。 */
    private String fix(String previous, String errorMessage, SkillSynthesisContext ctx) {
        String prompt = promptRegistry.render(FIX_PROMPT_KEY, Map.of(
                "errorMessage", errorMessage == null ? "" : errorMessage,
                "previousSkillMd", previous == null ? "" : previous,
                "gapDescription", ctx.gapDescription()
        ));
        return callLlm(prompt);
    }

    /** 统一 LLM 调用：skill_generation 场景，CHAT 能力，不走 outputSchema。 */
    private String callLlm(String prompt) {
        LlmResponse response = generationRouter.call(
                LlmScene.SKILL_GENERATION,
                prompt,
                null,
                null,
                null,
                GenerationCapability.CHAT,
                null);
        return response.content();
    }

    /** 解析自生成 Skill 目标根目录：{@code {skills.directory}/auto}。 */
    private Path autoDir() {
        return Paths.get(config.getDirectory(), AUTO_SUBDIR);
    }
}
