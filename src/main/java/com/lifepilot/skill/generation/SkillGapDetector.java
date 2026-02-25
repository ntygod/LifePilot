package com.lifepilot.skill.generation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.llm.LlmRouter;
import com.lifepilot.llm.LlmScene;
import com.lifepilot.skill.config.SkillConfigProperties;
import com.lifepilot.skill.registry.SkillRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Skill 缺口检测器 — 分析用户请求是否超出现有 Skill 能力范围。
 *
 * <p>检测流程：
 * <ol>
 *   <li>若 SkillRegistry 为空，直接判定缺口（置信度 0.9）</li>
 *   <li>语义搜索匹配度低于阈值时，使用 LLM 分析缺口内容</li>
 *   <li>LLM 调用失败时降级返回空结果</li>
 * </ol></p>
 *
 * @author zsg
 * @since 2026-02-25
 */
public class SkillGapDetector {

    private static final Logger log = LoggerFactory.getLogger(SkillGapDetector.class);

    /** 注册表为空时的默认置信度。 */
    private static final double EMPTY_REGISTRY_CONFIDENCE = 0.9;

    private final SkillRegistry skillRegistry;
    private final LlmRouter llmRouter;
    private final SkillConfigProperties config;
    private final ObjectMapper objectMapper;

    public SkillGapDetector(SkillRegistry skillRegistry,
                            LlmRouter llmRouter,
                            SkillConfigProperties config) {
        this.skillRegistry = skillRegistry;
        this.llmRouter = llmRouter;
        this.config = config;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 检测 Skill 缺口。
     *
     * @param userRequest 用户请求文本
     * @return 缺口描述，无缺口时返回 Optional.empty()
     */
    public Optional<SkillGap> detectGap(String userRequest) {
        // 1. 注册表为空 → 直接判定缺口
        if (skillRegistry.listSummaries().isEmpty()) {
            log.info("SkillRegistry 为空，直接判定缺口: request={}", userRequest);
            return Optional.of(new SkillGap(
                    EMPTY_REGISTRY_CONFIDENCE,
                    generateSuggestedId(userRequest),
                    userRequest,
                    userRequest,
                    List.of(),
                    "注册表为空，无可用 Skill"
            ));
        }

        // 2. 语义搜索匹配
        var searchResults = skillRegistry.search(userRequest);
        if (!searchResults.isEmpty()) {
            log.debug("语义搜索命中，无缺口: request={}, matchCount={}", userRequest, searchResults.size());
            return Optional.empty();
        }

        // 3. 搜索无结果 → 使用 LLM 分析缺口
        log.debug("语义搜索无结果，使用 LLM 分析缺口: request={}", userRequest);
        return analyzeGapWithLlm(userRequest);
    }

    /**
     * 使用 LLM 分析缺口内容。
     *
     * @param userRequest 用户请求
     * @return 缺口描述，LLM 调用失败时返回 Optional.empty()
     */
    private Optional<SkillGap> analyzeGapWithLlm(String userRequest) {
        String prompt = buildGapAnalysisPrompt(userRequest);
        try {
            var response = llmRouter.call(LlmScene.SKILL_GENERATION, prompt, null);
            return parseGapFromLlmResponse(response.content(), userRequest);
        } catch (Exception e) {
            log.warn("LLM 缺口分析调用失败，降级返回空结果: request={}, error={}", userRequest, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 构建缺口分析 Prompt。
     */
    private String buildGapAnalysisPrompt(String userRequest) {
        return """
                分析以下用户请求，判断是否需要创建新的 Skill。
                用户请求: %s
                
                请以 JSON 格式返回分析结果:
                {
                  "suggestedId": "建议的 Skill ID (kebab-case)",
                  "suggestedName": "建议的 Skill 名称",
                  "suggestedTools": ["建议使用的工具列表"],
                  "reason": "分析原因"
                }
                """.formatted(userRequest);
    }

    /**
     * 解析 LLM 响应为 SkillGap。
     */
    private Optional<SkillGap> parseGapFromLlmResponse(String content, String userRequest) {
        try {
            // 提取 JSON 内容（LLM 可能返回 markdown 代码块包裹的 JSON）
            String json = extractJson(content);
            JsonNode node = objectMapper.readTree(json);

            String suggestedId = node.has("suggestedId")
                    ? node.get("suggestedId").asText() : generateSuggestedId(userRequest);
            String suggestedName = node.has("suggestedName")
                    ? node.get("suggestedName").asText() : userRequest;
            String reason = node.has("reason")
                    ? node.get("reason").asText() : "LLM 分析建议创建新 Skill";

            List<String> suggestedTools = new ArrayList<>();
            if (node.has("suggestedTools") && node.get("suggestedTools").isArray()) {
                for (JsonNode tool : node.get("suggestedTools")) {
                    suggestedTools.add(tool.asText());
                }
            }

            return Optional.of(new SkillGap(
                    config.getAutoGeneration().getGapThreshold(),
                    suggestedId,
                    suggestedName,
                    userRequest,
                    suggestedTools,
                    reason
            ));
        } catch (Exception e) {
            log.warn("LLM 响应解析失败，降级返回空结果: error={}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 从 LLM 响应中提取 JSON 内容。
     *
     * <p>处理 LLM 可能返回的 markdown 代码块包裹格式。</p>
     */
    static String extractJson(String content) {
        String trimmed = content.trim();
        // 处理 ```json ... ``` 格式
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
     * 从用户请求生成建议的 Skill ID。
     *
     * <p>使用时间戳生成唯一 ID，格式为 auto-{timestamp}。</p>
     */
    static String generateSuggestedId(String userRequest) {
        return "auto-" + System.currentTimeMillis();
    }
}
