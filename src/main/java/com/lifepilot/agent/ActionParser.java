package com.lifepilot.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.agent.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LLM 输出解析器。
 *
 * <p>将 LLM 的 JSON 输出解析为对应的 Action 类型。
 * 使用多策略解析：直接 JSON 解析 → Markdown 代码块提取 → JSON 修复 → 部分 JSON 提取。</p>
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
public class ActionParser {

    private static final Logger log = LoggerFactory.getLogger(ActionParser.class);

    private final ObjectMapper objectMapper;

    /** Markdown JSON 代码块正则：匹配 ```json ... ``` 或 ``` ... ```。 */
    private static final Pattern MARKDOWN_JSON_BLOCK =
            Pattern.compile("```(?:json)?\\s*\\n?(.*?)\\n?```", Pattern.DOTALL);

    /** JSON 对象提取正则：匹配第一个 { 到最后一个 }。 */
    private static final Pattern JSON_OBJECT_EXTRACT =
            Pattern.compile("(\\{.*})", Pattern.DOTALL);

    public ActionParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper.copy()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    /**
     * 根据当前阶段将 LLM 输出解析为 Action。
     *
     * <p>使用多策略解析：
     * <ol>
     *   <li>直接 JSON 解析</li>
     *   <li>Markdown 代码块提取</li>
     *   <li>JSON 修复（移除尾部逗号、单引号转双引号等）</li>
     *   <li>部分 JSON 提取（提取第一个 { 到最后一个 }）</li>
     * </ol>
     *
     * <p>解析失败返回 ErrorRecovery(LLM_PARSE_FAILURE, recoverable=true)。</p>
     *
     * @param phase     当前 AgentPhase
     * @param llmOutput LLM 的原始输出
     * @return 解析后的 Action
     */
    public Action parse(AgentPhase phase, String llmOutput) {
        if (llmOutput == null || llmOutput.isBlank()) {
            log.warn("LLM 输出为空: phase={}", phase);
            return parseError("LLM 输出为空", llmOutput);
        }

        String trimmed = llmOutput.strip();

        // 策略 1: 直接 JSON 解析
        Action result = tryParse(phase, trimmed);
        if (result != null) {
            return result;
        }

        // 策略 2: Markdown 代码块提取
        String extracted = extractMarkdownJsonBlock(trimmed);
        if (extracted != null && !extracted.equals(trimmed)) {
            result = tryParse(phase, extracted);
            if (result != null) {
                log.debug("从 Markdown 代码块提取 JSON 成功: phase={}", phase);
                return result;
            }
        }

        // 策略 3: JSON 修复
        String fixed = fixJson(trimmed);
        if (!fixed.equals(trimmed)) {
            result = tryParse(phase, fixed);
            if (result != null) {
                log.debug("JSON 修复后解析成功: phase={}", phase);
                return result;
            }
        }

        // 策略 4: 部分 JSON 提取
        String partial = extractJsonObject(trimmed);
        if (partial != null && !partial.equals(trimmed)) {
            result = tryParse(phase, partial);
            if (result != null) {
                log.debug("部分 JSON 提取成功: phase={}", phase);
                return result;
            }
        }

        // 策略 5: EXECUTING 阶段特殊处理 - 尝试提取 assistant_response
        if (phase == AgentPhase.EXECUTING) {
            result = tryExtractResponseFromExecuting(trimmed);
            if (result != null) {
                log.debug("从 EXECUTING 阶段输出提取 ResponseGenerated 成功");
                return result;
            }
        }

        // 所有策略都失败，尝试最后一次解析以获取详细错误信息
        String lastError = null;
        try {
            switch (phase) {
                case UNDERSTANDING -> objectMapper.readValue(trimmed, Action.IntentUnderstood.class);
                case PLANNING -> objectMapper.readValue(trimmed, Action.PlanGenerated.class);
                case REFLECTING -> objectMapper.readValue(trimmed, Action.ReflectionComplete.class);
                case RESPONDING -> objectMapper.readValue(trimmed, Action.ResponseGenerated.class);
                case EXECUTING -> {
                    // EXECUTING 阶段不映射到固定 Action 类型，这里仅校验 JSON 语法，
                    // 以便在日志中输出真实的 JSON 解析错误，而不是误报 “error=null”。
                    objectMapper.readTree(trimmed);
                }
                default -> {
                }
            }
        } catch (JsonProcessingException e) {
            lastError = e.getMessage();
        }
        
        // 记录详细的失败信息
        String errorMsg;
        if (phase == AgentPhase.EXECUTING) {
            // EXECUTING 阶段的失败，更多是“结构不符合期望”而不是“无法解析为某个 Action”
            errorMsg = lastError != null
                    ? String.format("EXECUTING 阶段 JSON 语法解析失败：%s", lastError)
                    : "EXECUTING 阶段输出无法解析为可用结果（未找到 response 字段 / 结构不匹配）";
        } else {
            errorMsg = lastError != null
                ? String.format("JSON 解析失败：%s", lastError)
                : "JSON 解析失败：LLM 输出不是有效的 JSON 格式";
        }
        log.warn("LLM 输出解析失败，所有策略都失败: phase={}, error={}, output={}", 
                phase, lastError, trimmed.length() > 500 ? trimmed.substring(0, 500) + "..." : trimmed);
        return parseError(errorMsg, trimmed);
    }

    /**
     * 尝试解析 JSON。
     *
     * @param phase     当前阶段
     * @param jsonStr   JSON 字符串
     * @return 解析成功返回 Action，失败返回 null
     */
    private Action tryParse(AgentPhase phase, String jsonStr) {
        try {
            return switch (phase) {
                case UNDERSTANDING -> objectMapper.readValue(jsonStr, Action.IntentUnderstood.class);
                case PLANNING -> objectMapper.readValue(jsonStr, Action.PlanGenerated.class);
                case REFLECTING -> objectMapper.readValue(jsonStr, Action.ReflectionComplete.class);
                case RESPONDING -> objectMapper.readValue(jsonStr, Action.ResponseGenerated.class);
                case EXECUTING, TERMINATED -> null;
            };
        } catch (JsonProcessingException e) {
            // 记录详细的解析错误，但只在DEBUG级别
            log.debug("JSON 解析失败: phase={}, error={}, json={}", phase, e.getMessage(),
                    jsonStr.length() > 300 ? jsonStr.substring(0, 300) + "..." : jsonStr);
            return null;
        }
    }

    /**
     * 从 Markdown 代码块中提取 JSON。
     *
     * @param content 原始内容
     * @return 提取的 JSON，未找到返回 null
     */
    private String extractMarkdownJsonBlock(String content) {
        Matcher matcher = MARKDOWN_JSON_BLOCK.matcher(content);
        if (matcher.find()) {
            return matcher.group(1).strip();
        }
        return null;
    }

    /**
     * 修复常见的 JSON 格式问题。
     *
     * @param content 原始内容
     * @return 修复后的内容
     */
    private String fixJson(String content) {
        String fixed = content;
        // 移除尾部逗号（在 } 或 ] 前）
        fixed = fixed.replaceAll(",\\s*([}\\]])", "$1");
        // 单引号转双引号（简单处理，不处理字符串内的单引号）
        fixed = fixed.replaceAll("'([^']*)':", "\"$1\":");
        // 移除注释（简单处理）
        fixed = fixed.replaceAll("//.*", "");
        fixed = fixed.replaceAll("/\\*.*?\\*/", "");
        return fixed;
    }

    /**
     * 提取 JSON 对象（从第一个 { 到最后一个 }）。
     * 如果 JSON 被截断，尝试补全缺失的闭合括号。
     *
     * @param content 原始内容
     * @return 提取的 JSON 对象，未找到返回 null
     */
    private String extractJsonObject(String content) {
        Matcher matcher = JSON_OBJECT_EXTRACT.matcher(content);
        if (matcher.find()) {
            String extracted = matcher.group(1);
            // 检查是否被截断（缺少闭合括号）
            long openBraces = extracted.chars().filter(c -> c == '{').count();
            long closeBraces = extracted.chars().filter(c -> c == '}').count();
            long openBrackets = extracted.chars().filter(c -> c == '[').count();
            long closeBrackets = extracted.chars().filter(c -> c == ']').count();
            
            // 如果缺少闭合括号，尝试补全
            if (openBraces > closeBraces || openBrackets > closeBrackets) {
                StringBuilder fixed = new StringBuilder(extracted);
                // 补全缺失的数组闭合括号
                for (long i = closeBrackets; i < openBrackets; i++) {
                    fixed.append(']');
                }
                // 补全缺失的对象闭合括号
                for (long i = closeBraces; i < openBraces; i++) {
                    fixed.append('}');
                }
                return fixed.toString();
            }
            return extracted;
        }
        return null;
    }

    /**
     * 尝试从 EXECUTING 阶段的输出中提取 ResponseGenerated。
     * 
     * <p>当 LLM 在 EXECUTING 阶段返回包含响应内容的 JSON 时，
     * 说明任务已完成，可以直接生成响应。</p>
     * 
     * <p>支持的格式：
     * <ul>
     *   <li>直接响应字段：assistant_response, response, content, message, reply</li>
     *   <li>工具调用格式：tool_calls[].parameters.response (tool_name=core_response)</li>
     *   <li>步骤详情格式：step_details.parameters.response (tool_name=core_response)</li>
     *   <li>步骤执行格式：step_execution.parameters.response (tool_name=core_response)</li>
     * </ul>
     *
     * @param jsonStr JSON 字符串
     * @return 如果成功提取则返回 ResponseGenerated，否则返回 null
     */
    private Action tryExtractResponseFromExecuting(String jsonStr) {
        try {
            JsonNode root = objectMapper.readTree(jsonStr);
            List<String> suggestions = new ArrayList<>();
            
            // 策略 1: 查找 assistant_response 字段
            JsonNode assistantResponse = root.get("assistant_response");
            if (assistantResponse != null && assistantResponse.isTextual()) {
                String content = assistantResponse.asText();
                extractSuggestions(root, suggestions);
                log.debug("从 EXECUTING 阶段输出提取 assistant_response: length={}", content.length());
                return new Action.ResponseGenerated(content, suggestions);
            }
            
            // 策略 2: 查找其他可能的直接响应字段
            String[] possibleFields = {"response", "content", "message", "reply"};
            for (String field : possibleFields) {
                JsonNode fieldNode = root.get(field);
                if (fieldNode != null && fieldNode.isTextual()) {
                    String content = fieldNode.asText();
                    extractSuggestions(root, suggestions);
                    log.debug("从 EXECUTING 阶段输出提取 {} 字段: length={}", field, content.length());
                    return new Action.ResponseGenerated(content, suggestions);
                }
            }
            
            // 策略 3: 从工具调用中提取 (tool_calls[].parameters.response)
            JsonNode toolCalls = root.get("tool_calls");
            if (toolCalls != null && toolCalls.isArray()) {
                for (JsonNode toolCall : toolCalls) {
                    String toolName = getTextValue(toolCall, "tool_name");
                    if ("core_response".equals(toolName)) {
                        JsonNode parameters = toolCall.get("parameters");
                        if (parameters != null) {
                            String response = getTextValue(parameters, "response");
                            if (response != null && !response.isBlank()) {
                                extractSuggestions(root, suggestions);
                                log.debug("从 tool_calls 提取 core_response: length={}", response.length());
                                return new Action.ResponseGenerated(response, suggestions);
                            }
                        }
                    }
                }
            }
            
            // 策略 4: 从步骤详情中提取 (step_details.parameters.response)
            JsonNode stepDetails = root.get("step_details");
            if (stepDetails != null && stepDetails.isObject()) {
                String toolName = getTextValue(stepDetails, "tool_name");
                if ("core_response".equals(toolName)) {
                    JsonNode parameters = stepDetails.get("parameters");
                    if (parameters != null) {
                        String response = getTextValue(parameters, "response");
                        if (response != null && !response.isBlank()) {
                            extractSuggestions(root, suggestions);
                            log.debug("从 step_details 提取 core_response: length={}", response.length());
                            return new Action.ResponseGenerated(response, suggestions);
                        }
                    }
                }
            }
            
            // 策略 5: 从步骤执行中提取 (step_execution.parameters.response)
            JsonNode stepExecution = root.get("step_execution");
            if (stepExecution != null && stepExecution.isObject()) {
                String toolName = getTextValue(stepExecution, "tool_name");
                if ("core_response".equals(toolName)) {
                    JsonNode parameters = stepExecution.get("parameters");
                    if (parameters != null) {
                        String response = getTextValue(parameters, "response");
                        if (response != null && !response.isBlank()) {
                            extractSuggestions(root, suggestions);
                            log.debug("从 step_execution 提取 core_response: length={}", response.length());
                            return new Action.ResponseGenerated(response, suggestions);
                        }
                    }
                }
            }
            
        } catch (JsonProcessingException e) {
            // JSON 解析失败，返回 null
            log.debug("EXECUTING 阶段 JSON 解析失败: {}", e.getMessage());
        }
        return null;
    }
    
    /**
     * 从 JSON 节点中提取文本值。
     *
     * @param node JSON 节点
     * @param fieldName 字段名
     * @return 文本值，不存在或不是文本类型返回 null
     */
    private String getTextValue(JsonNode node, String fieldName) {
        if (node == null) {
            return null;
        }
        JsonNode fieldNode = node.get(fieldName);
        if (fieldNode != null && fieldNode.isTextual()) {
            return fieldNode.asText();
        }
        return null;
    }
    
    /**
     * 从根节点提取 suggestions 数组。
     *
     * @param root 根节点
     * @param suggestions 输出列表
     */
    private void extractSuggestions(JsonNode root, List<String> suggestions) {
        JsonNode suggestionsNode = root.get("suggestions");
        if (suggestionsNode != null && suggestionsNode.isArray()) {
            for (JsonNode suggestion : suggestionsNode) {
                if (suggestion.isTextual()) {
                    suggestions.add(suggestion.asText());
                }
            }
        }
    }

    /** 构建解析错误的 ErrorRecovery Action。 */
    private Action parseError(String message, String originalOutput) {
        return new Action.ErrorRecovery(
                AgentErrorType.LLM_PARSE_FAILURE, message, true, originalOutput);
    }
}
