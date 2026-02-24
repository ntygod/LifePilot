package com.lifepilot.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.agent.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * LLM 输出解析器。
 *
 * <p>将 LLM 的 JSON 输出解析为对应的 Action 类型。
 * 使用 Jackson ObjectMapper 进行 JSON 反序列化。</p>
 *
 * <p>阶段到 Action 类型的映射：
 * <ul>
 *   <li>UNDERSTANDING → IntentUnderstood</li>
 *   <li>PLANNING → PlanGenerated</li>
 *   <li>REFLECTING → ReflectionComplete</li>
 *   <li>RESPONDING → ResponseGenerated</li>
 *   <li>EXECUTING → 不通过 ActionParser 解析</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-07-20
 */
@Service
public class ActionParser {

    private static final Logger log = LoggerFactory.getLogger(ActionParser.class);

    private final ObjectMapper objectMapper;

    public ActionParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper.copy()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    /**
     * 根据当前阶段将 LLM 输出解析为 Action。
     *
     * <p>解析失败返回 ErrorRecovery(LLM_PARSE_FAILURE, recoverable=true)。</p>
     *
     * @param phase     当前 AgentPhase
     * @param llmOutput LLM 的 JSON 输出
     * @return 解析后的 Action
     */
    public Action parse(AgentPhase phase, String llmOutput) {
        if (llmOutput == null || llmOutput.isBlank()) {
            return parseError("LLM 输出为空");
        }

        try {
            return switch (phase) {
                case UNDERSTANDING -> objectMapper.readValue(llmOutput, Action.IntentUnderstood.class);
                case PLANNING -> objectMapper.readValue(llmOutput, Action.PlanGenerated.class);
                case REFLECTING -> objectMapper.readValue(llmOutput, Action.ReflectionComplete.class);
                case RESPONDING -> objectMapper.readValue(llmOutput, Action.ResponseGenerated.class);
                case EXECUTING, TERMINATED -> parseError("不支持的解析阶段: " + phase);
            };
        } catch (JsonProcessingException e) {
            log.warn("LLM 输出解析失败: phase={}, error={}", phase, e.getMessage());
            return parseError("JSON 解析失败: " + e.getMessage());
        }
    }

    /**
     * 将 Action 序列化为 JSON。
     *
     * @param action Action 实例
     * @return JSON 字符串
     */
    public String serialize(Action action) {
        try {
            return objectMapper.writeValueAsString(action);
        } catch (JsonProcessingException e) {
            log.warn("Action 序列化失败: type={}, error={}",
                    action.getClass().getSimpleName(), e.getMessage());
            return "{}";
        }
    }

    /** 构建解析错误的 ErrorRecovery Action。 */
    private Action parseError(String message) {
        return new Action.ErrorRecovery(
                AgentErrorType.LLM_PARSE_FAILURE, message, true, null);
    }
}
