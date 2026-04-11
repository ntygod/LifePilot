package com.lifepilot.interaction.web.controller;

import com.lifepilot.agent.CancellationToken;
import com.lifepilot.agent.orchestration.AgentOrchestrator;
import com.lifepilot.agent.context.AssembledContext;
import com.lifepilot.agent.context.ContextMessageFormatter;
import com.lifepilot.agent.context.ContextAssembler;
import com.lifepilot.agent.context.TokenBudget;
import com.lifepilot.agent.model.AgentRequest;
import com.lifepilot.agent.model.AgentResponse;
import com.lifepilot.agent.model.ReactAgentState;
import com.lifepilot.interaction.model.InteractionSource;
import com.lifepilot.interaction.model.TokenUsage;
import com.lifepilot.interaction.web.model.ApiResponse;
import com.lifepilot.interaction.web.model.*;
import com.lifepilot.interaction.web.model.ContextPreviewResponse.SegmentInfo;
import com.lifepilot.interaction.web.model.ContextPreviewResponse.TokenBudgetInfo;
import com.lifepilot.interaction.web.sse.SseEventType;
import com.lifepilot.interaction.web.sse.SseSessionManager;
import com.lifepilot.knowledge.KnowledgeBaseManager;
import com.lifepilot.knowledge.model.KnowledgeBase;
import com.lifepilot.multiagent.model.AgentBudget;
import com.lifepilot.multiagent.model.AgentDefinition;
import com.lifepilot.multiagent.model.AgentSource;
import com.lifepilot.multiagent.registry.AgentRegistry;
import com.lifepilot.multiagent.config.MultiAgentProperties;
import com.lifepilot.multiagent.loader.AgentMarkdownLoader;
import com.lifepilot.multiagent.loader.AgentMarkdownParser;
import com.lifepilot.multiagent.loader.AgentMarkdownSerializer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Agent 管理 REST Controller。
 *
 * <p>提供 Agent 测试对话等端点。</p>
 *
 * @author zsg
 * @since 2026-02-28
 */
@RestController
@RequestMapping("/api/agents")
@ConditionalOnProperty(name = "lifepilot.gateway.channels.web.enabled", havingValue = "true")
public class AgentController {

    private static final Logger log = LoggerFactory.getLogger(AgentController.class);

    private final AgentRegistry agentRegistry;
    @Nullable private final AgentOrchestrator agentOrchestrator;
    private final KnowledgeBaseManager knowledgeBaseManager;
    private final ContextAssembler contextAssembler;
    private final AgentMarkdownParser markdownParser;
    private final AgentMarkdownSerializer markdownSerializer;
    private final AgentMarkdownLoader markdownLoader;
    private final MultiAgentProperties multiAgentConfig;
    @Nullable private final SseSessionManager sseSessionManager;

    public AgentController(AgentRegistry agentRegistry,
                           @Nullable AgentOrchestrator agentOrchestrator,
                           KnowledgeBaseManager knowledgeBaseManager,
                           ContextAssembler contextAssembler,
                           AgentMarkdownParser markdownParser,
                           AgentMarkdownSerializer markdownSerializer,
                           AgentMarkdownLoader markdownLoader,
                           MultiAgentProperties multiAgentConfig,
                           @Nullable SseSessionManager sseSessionManager) {
        this.agentRegistry = agentRegistry;
        this.agentOrchestrator = agentOrchestrator;
        this.knowledgeBaseManager = knowledgeBaseManager;
        this.contextAssembler = contextAssembler;
        this.markdownParser = markdownParser;
        this.markdownSerializer = markdownSerializer;
        this.markdownLoader = markdownLoader;
        this.multiAgentConfig = multiAgentConfig;
        this.sseSessionManager = sseSessionManager;
    }

    /**
     * 获取 Agent 列表。
     *
     * <p>支持关键词搜索、类型过滤、状态过滤和标签过滤。</p>
     *
     * @param q      关键词搜索（名称/描述）
     * @param type   Agent 类型过滤（"default"/"custom"/"workflow"）
     * @param status 状态过滤（"enabled"/"disabled"）
     * @param tags   标签过滤（多个标签，逗号分隔）
     * @return Agent 列表
     */
    @GetMapping
    public ApiResponse<List<AgentSummary>> listAgents(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String tags) {
        log.debug("查询 Agent 列表: q={}, type={}, status={}, tags={}", q, type, status, tags);

        // 1. 获取所有 Agent
        List<AgentDefinition> allAgents = agentRegistry.listAll();

        // 2. 转换为 AgentSummary 并应用过滤
        List<AgentSummary> summaries = allAgents.stream()
                .map(this::toAgentSummary)
                .filter(summary -> matchesQuery(summary, q))
                .filter(summary -> matchesType(summary, type))
                .filter(summary -> matchesStatus(summary, status))
                .filter(summary -> matchesTags(summary, tags))
                .collect(Collectors.toList());

        log.info("返回 Agent 列表: 总数={}, 过滤后={}", allAgents.size(), summaries.size());
        return ApiResponse.ok(summaries);
    }

    /**
     * 将 AgentDefinition 转换为 AgentSummary。
     */
    private AgentSummary toAgentSummary(AgentDefinition agent) {
        // 判断类型
        String agentType = determineType(agent.source());
        String agentSource = determineSource(agent.source());

        // 判断状态（从 metadata 中提取）
        String agentStatus = extractStatus(agent.metadata());
        boolean enabled = "enabled".equalsIgnoreCase(agentStatus);

        // 提取标签（从 metadata 中）
        List<String> agentTags = extractTags(agent.metadata());

        // 提取模型 ID（从 preferredProvider 或 metadata）
        String preferredProviderId = resolvePreferredProviderId(agent);

        // 知识库数量（从 metadata 中的 knowledgeBaseIds 获取）
        List<String> kbIds = extractKnowledgeBaseIds(agent.metadata());
        int knowledgeBaseCount = kbIds.size();

        // 创建时间和更新时间（从 metadata 或使用当前时间）
        Instant createdAt = extractInstant(agent.metadata(), "createdAt", Instant.now());
        Instant updatedAt = extractInstant(agent.metadata(), "updatedAt", Instant.now());

        return new AgentSummary(
                agent.id(),
                agent.name(),
                agent.description(),
                agentType,
                agentSource,
                preferredProviderId,
                knowledgeBaseCount,
                updatedAt,
                createdAt,
                enabled,
                agentStatus,
                agentTags
        );
    }

    /**
     * 根据 AgentSource 确定 Agent 类型标签。
     */
    private String determineType(AgentSource source) {
        if (source instanceof AgentSource.Builtin) {
            return "default";
        } else if (source instanceof AgentSource.MarkdownDefined) {
            return "custom";
        } else if (source instanceof AgentSource.Marketplace) {
            return "marketplace";
        }
        return "custom"; // 默认
    }


    /**
     * 从 metadata 中提取标签。
     */
    private String determineSource(AgentSource source) {
        if (source instanceof AgentSource.Builtin) {
            return "Builtin";
        } else if (source instanceof AgentSource.MarkdownDefined) {
            return "MarkdownDefined";
        } else if (source instanceof AgentSource.Marketplace) {
            return "Marketplace";
        }
        return source.getClass().getSimpleName();
    }

    private List<String> extractTags(java.util.Map<String, String> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return List.of();
        }

        String tagsStr = metadata.get("tags");
        if (tagsStr == null || tagsStr.isBlank()) {
            return List.of();
        }

        // 支持逗号分隔的字符串或 JSON 数组字符串
        List<String> tags = new ArrayList<>();
        if (tagsStr.startsWith("[")) {
            // JSON 数组格式，简单解析
            String content = tagsStr.substring(1, tagsStr.length() - 1);
            for (String tag : content.split(",")) {
                String trimmed = tag.trim().replaceAll("^\"|\"$", "");
                if (!trimmed.isEmpty()) {
                    tags.add(trimmed);
                }
            }
        } else {
            // 逗号分隔格式
            for (String tag : tagsStr.split(",")) {
                String trimmed = tag.trim();
                if (!trimmed.isEmpty()) {
                    tags.add(trimmed);
                }
            }
        }
        return tags;
    }

    /**
     * 从 metadata 中提取 Instant。
     */
    private Instant extractInstant(java.util.Map<String, String> metadata, String key, Instant defaultValue) {
        if (metadata == null || metadata.isEmpty()) {
            return defaultValue;
        }
        String value = metadata.get(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Instant.parse(value);
        } catch (Exception e) {
            log.debug("无法解析时间戳: key={}, value={}", key, value);
            return defaultValue;
        }
    }

    /**
     * 检查是否匹配关键词查询。
     */
    private boolean matchesQuery(AgentSummary summary, String q) {
        if (q == null || q.isBlank()) {
            return true;
        }
        String lowerQ = q.toLowerCase();
        return (summary.name() != null && summary.name().toLowerCase().contains(lowerQ))
                || (summary.description() != null && summary.description().toLowerCase().contains(lowerQ));
    }

    /**
     * 检查是否匹配类型过滤。
     */
    private boolean matchesType(AgentSummary summary, String type) {
        if (type == null || type.isBlank()) {
            return true;
        }
        return type.equalsIgnoreCase(summary.type());
    }

    /**
     * 检查是否匹配状态过滤。
     */
    private boolean matchesStatus(AgentSummary summary, String status) {
        if (status == null || status.isBlank()) {
            return true;
        }
        return status.equalsIgnoreCase(summary.status());
    }

    /**
     * 检查是否匹配标签过滤。
     */
    private boolean matchesTags(AgentSummary summary, String tagsParam) {
        if (tagsParam == null || tagsParam.isBlank()) {
            return true;
        }

        List<String> filterTags = new ArrayList<>();
        for (String tag : tagsParam.split(",")) {
            String trimmed = tag.trim();
            if (!trimmed.isEmpty()) {
                filterTags.add(trimmed.toLowerCase());
            }
        }

        if (filterTags.isEmpty()) {
            return true;
        }

        // 检查 summary 的标签是否包含任一过滤标签
        List<String> summaryTags = summary.tags() != null
                ? summary.tags().stream().map(String::toLowerCase).toList()
                : List.of();

        return filterTags.stream().anyMatch(summaryTags::contains);
    }

    /**
     * Agent 测试对话接口。
     *
     * <p>使用指定 Agent 配置进行对话测试，不保存会话历史。</p>
     *
     * @param id      Agent ID
     * @param request 测试消息请求
     * @return ChatResponse 包含响应内容和 Token 使用情况
     */
    @PostMapping("/{id}/test-chat")
    public ApiResponse<?> testChat(@PathVariable String id,
                                       @RequestBody TestChatRequest request) {
        if (agentOrchestrator == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Agent 引擎未启用（LLM 不可用）");
        }
        log.info("Agent 测试对话请求: agentId={}, messageLength={}", id, request.message().length());

        // 1. 查找 Agent 定义
        var agentOpt = agentRegistry.find(id);
        if (agentOpt.isEmpty()) {
            log.warn("Agent 不存在: id={}", id);
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Agent 不存在: id=" + id);
        }

        AgentDefinition agent = agentOpt.get();
        String preferredProviderId = resolvePreferredProviderId(agent);

        // 2. 验证消息内容
        if (request.message().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "消息内容不能为空");
        }

        try {
            // 3. 构建 AgentRequest（使用独立的测试会话 ID）
            String testSessionId = "test:" + UUID.randomUUID().toString();
            var agentRequest = new AgentRequest(
                    request.message(),
                    testSessionId,
                    InteractionSource.channel("web-test", "web", "web.test"),
                    null,
                    agent.systemPrompt(),
                    agent.budget().toAgentBudget(),
                    null, // 无父 traceId
                    0,    // 深度为 0
                    preferredProviderId,
                    agent.allowedTools(),
                    null, // 测试对话暂不携带多模态内容
                    null  // temperature
            );

            // 4. 执行 Agent 对话
            AgentResponse agentResponse = agentOrchestrator.run(agentRequest);

            // 5. 构建 ChatResponse
            String entryId = UUID.randomUUID().toString();
            TokenUsage tokenUsage = agentResponse.tokenUsage() != null ? agentResponse.tokenUsage() : new TokenUsage(
                    0, // promptTokens（AgentResponse 中没有详细分解）
                    0, // completionTokens
                    agentResponse.tokensUsed(), // totalTokens
                    "unknown"
            );

            ChatResponse chatResponse = new ChatResponse(
                    entryId,
                    agentResponse.turnId(),
                    agentResponse.content(),
                    null, // a2ui（测试对话暂不支持）
                    tokenUsage,
                    agentResponse.traceId()
            );

            log.info("Agent 测试对话完成: agentId={}, entryId={}, tokensUsed={}, steps={}",
                    id, entryId, agentResponse.tokensUsed(), agentResponse.stepCount());

            return ApiResponse.ok(chatResponse);

        } catch (Exception e) {
            log.error("Agent 测试对话异常: agentId={}, error={}", id, e.getMessage(), e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Agent 测试对话失败: " + e.getMessage());
        }
    }

    /**
     * Agent 流式测试对话接口。
     *
     * <p>使用指定 Agent 配置进行流式对话测试，不保存会话历史和记忆。
     * 返回 SseEmitter 推送 TOKEN / DONE / ERROR 事件。</p>
     *
     * @param id      Agent ID
     * @param request 测试消息请求
     * @return SseEmitter 流式事件
     */
    @PostMapping("/{id}/test-chat/stream")
    public SseEmitter testChatStream(@PathVariable String id,
                                     @RequestBody TestChatRequest request) {
        // 前置校验
        if (agentOrchestrator == null || sseSessionManager == null) {
            var emitter = new SseEmitter(0L);
            try {
                emitter.send(SseEmitter.event()
                        .name(SseEventType.ERROR)
                        .data(Map.of("code", 503, "message", "Agent 引擎或 SSE 管理器未启用")));
                emitter.complete();
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
            return emitter;
        }

        if (request.message() == null || request.message().isBlank()) {
            var emitter = new SseEmitter(0L);
            try {
                emitter.send(SseEmitter.event()
                        .name(SseEventType.ERROR)
                        .data(Map.of("code", 400, "message", "消息内容不能为空")));
                emitter.complete();
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
            return emitter;
        }

        // 查找 Agent 定义
        var agentOpt = agentRegistry.find(id);
        if (agentOpt.isEmpty()) {
            var emitter = new SseEmitter(0L);
            try {
                emitter.send(SseEmitter.event()
                        .name(SseEventType.ERROR)
                        .data(Map.of("code", 404, "message", "Agent 不存在: id=" + id)));
                emitter.complete();
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
            return emitter;
        }

        AgentDefinition agent = agentOpt.get();
        String preferredProviderId = resolvePreferredProviderId(agent);

        // 创建 SSE 流
        String streamId = UUID.randomUUID().toString();
        SseEmitter emitter = sseSessionManager.createEmitter(streamId);

        // 构建 AgentRequest（测试会话 ID 以 "test:" 开头，AgentOrchestrator 会跳过持久化）
        String testSessionId = "test:" + UUID.randomUUID();
        var agentRequest = new AgentRequest(
                request.message(),
                testSessionId,
                InteractionSource.channel("web-test", "web", "web.test"),
                null,
                agent.systemPrompt(),
                agent.budget().toAgentBudget(),
                null, 0,
                preferredProviderId,
                agent.allowedTools(),
                null, // 测试对话暂不携带多模态内容
                null  // temperature
        );

        // 注册取消令牌并异步执行
        var cancellationToken = new CancellationToken();
        sseSessionManager.registerCancellationToken(streamId, cancellationToken);

        Thread.startVirtualThread(() -> {
            try {
                agentOrchestrator.runStreaming(agentRequest, streamId, sseSessionManager, cancellationToken);
            } catch (Exception e) {
                log.error("流式测试对话异常: agentId={}, streamId={}, error={}", id, streamId, e.getMessage(), e);
                sseSessionManager.sendEvent(streamId, SseEventType.ERROR,
                        Map.of("code", 500, "message", "流式测试对话失败: " + e.getMessage()));
                sseSessionManager.closeEmitter(streamId);
            }
        });

        log.info("Agent 流式测试对话开始: agentId={}, streamId={}", id, streamId);
        return emitter;
    }

    /**
     * 上下文组装预览接口。
     *
     * <p>使用指定 Agent 配置和测试消息，调用 ContextAssembler 组装上下文，
     * 返回各段落内容、Token 预算分配和实际消耗。</p>
     *
     * @param id      Agent ID
     * @param request 预览请求（包含测试消息和可选会话 ID）
     * @return ContextPreviewResponse 上下文组装结果
     */
    @PostMapping("/{id}/context-preview")
    public ApiResponse<?> contextPreview(@PathVariable String id,
                                            @RequestBody ContextPreviewRequest request) {
        log.info("上下文组装预览请求: agentId={}, messageLength={}", id, request.message().length());

        // 1. 查找 Agent 定义
        var agentOpt = agentRegistry.find(id);
        if (agentOpt.isEmpty()) {
            log.warn("Agent 不存在: id={}", id);
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Agent 不存在: id=" + id);
        }

        AgentDefinition agent = agentOpt.get();
        String preferredProviderId = resolvePreferredProviderId(agent);

        try {
            // 2. 构造临时 ReactAgentState（用于上下文组装预览）
            String sessionId = request.sessionId() != null
                    ? request.sessionId()
                    : "preview:" + UUID.randomUUID();
            var agentRequest = new AgentRequest(
                    request.message(),
                    sessionId,
                    InteractionSource.channel("web-preview", "web", "web.preview"),
                    null,
                    agent.systemPrompt(),
                    agent.budget().toAgentBudget(),
                    null, 0,
                    preferredProviderId,
                    agent.allowedTools(),
                    null,
                    null
            );
            ReactAgentState state = ReactAgentState.init(agentRequest, agent.budget().toAgentBudget());

            // 3. 调用 ContextAssembler 组装上下文
            AssembledContext assembled = contextAssembler.assemble(state);

            // 4. 映射为响应 DTO
            TokenBudget tb = assembled.tokenBudget();
            var segments = new LinkedHashMap<String, SegmentInfo>();
            segments.put(ContextPreviewResponse.SEGMENT_SYSTEM_PROMPT,
                    new SegmentInfo(assembled.systemPrompt(), tb.systemPromptUsed()));
            segments.put(ContextPreviewResponse.SEGMENT_CONTEXT_MESSAGES,
                    new SegmentInfo(
                            ContextMessageFormatter.serializeForPreview(assembled.contextMessages()),
                            tb.memoryUsed()));
            segments.put(ContextPreviewResponse.SEGMENT_HISTORY_MESSAGES,
                    new SegmentInfo(
                            ContextMessageFormatter.serializeForPreview(assembled.historyMessages()),
                            tb.historyUsed() + tb.toolResultUsed()));
            segments.put(ContextPreviewResponse.SEGMENT_CURRENT_USER_PROMPT,
                    new SegmentInfo(assembled.userPrompt(), estimateTokens(assembled.userPrompt())));

            var tokenBudgetInfo = new TokenBudgetInfo(
                    tb.systemPromptBudget(), tb.historyBudget(), tb.memoryBudget(),
                    tb.toolSchemaBudget(), tb.toolResultBudget(), tb.reservedBuffer(),
                    tb.systemPromptUsed(), tb.historyUsed(), tb.memoryUsed(),
                    tb.toolSchemaUsed(), tb.toolResultUsed()
            );

            var response = new ContextPreviewResponse(
                    segments, tokenBudgetInfo,
                    assembled.totalTokens(), tb.totalBudget(),
                    assembled.degraded()
            );

            log.info("上下文组装预览完成: agentId={}, totalTokens={}, degraded={}",
                    id, assembled.totalTokens(), assembled.degraded());

            return ApiResponse.ok(response);

        } catch (Exception e) {
            log.error("上下文组装预览异常: agentId={}, error={}", id, e.getMessage(), e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "上下文组装预览失败: " + e.getMessage());
        }
    }

    /**
     * 获取 Agent 详情。
     *
     * @param text Agent ID
     * @return Agent 详情
     */
    private int estimateTokens(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        return Math.max(1, text.length() / 4);
    }

    @GetMapping("/{id}")
    public ApiResponse<?> getAgent(@PathVariable String id) {
        log.debug("查询 Agent 详情: id={}", id);

        var agentOpt = agentRegistry.find(id);
        if (agentOpt.isEmpty()) {
            log.warn("Agent 不存在: id={}", id);
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Agent 不存在: id=" + id);
        }

        AgentDefinition agent = agentOpt.get();
        AgentDetail detail = toAgentDetail(agent);

        return ApiResponse.ok(detail);
    }

    /**
     * 创建 Agent。
     *
     * @param request 创建请求
     * @return 创建的 Agent 详情
     */
    @PostMapping
    public ApiResponse<?> createAgent(@RequestBody CreateAgentRequest request) {
        log.info("创建 Agent: name={}", request.name());

        // 1. 验证必填字段
        if (request.name() == null || request.name().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Agent 名称不能为空");
        }
        if (request.systemPrompt() != null && request.systemPrompt().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "System Prompt 不能为空");
        }

        // 2. 生成 Agent ID
        String agentId = "custom-" + UUID.randomUUID().toString().substring(0, 8);
        Instant now = Instant.now();
        String agentName = request.name().strip();
        String description = normalizeOptionalText(request.description());

        // 3. 构建 metadata
        Map<String, String> metadata = buildMetadata(request, now, now);

        // 4. 构建 AgentDefinition
        AgentDefinition agentDef = AgentDefinition.builder()
                .id(agentId)
                .name(agentName)
                .description(description != null ? description : "")
                .systemPrompt(resolveSystemPrompt(agentName, description, request.systemPrompt()))
                .allowedTools(request.toolIds() != null ? request.toolIds() : List.of())
                .budget(AgentBudget.DEFAULT)
                .preferredProvider(normalizeOptionalText(request.preferredProviderId()))
                .source(new AgentSource.MarkdownDefined(null, now))
                .metadata(metadata)
                .build();

        // 5. 注册 Agent
        boolean registered = agentRegistry.register(agentDef);
        if (!registered) {
            log.error("Agent 注册失败: id={}", agentId);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Agent 注册失败");
        }

        // 6. 返回 Agent 详情
        AgentDetail detail = toAgentDetail(agentDef);
        log.info("Agent 创建成功: id={}, name={}", agentId, request.name());
        return ApiResponse.ok(detail);
    }

    /**
     * 更新 Agent。
     *
     * @param id      Agent ID
     * @param request 更新请求
     * @return 更新后的 Agent 详情
     */
    @PutMapping("/{id}")
    public ApiResponse<?> updateAgent(@PathVariable String id, @RequestBody UpdateAgentRequest request) {
        log.info("更新 Agent: id={}", id);

        // 1. 查找现有 Agent
        var agentOpt = agentRegistry.find(id);
        if (agentOpt.isEmpty()) {
            log.warn("Agent 不存在: id={}", id);
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Agent 不存在: id=" + id);
        }

        if (request.name() != null && request.name().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Agent 名称不能为空");
        }
        if (request.systemPrompt() != null && request.systemPrompt().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "System Prompt 不能为空");
        }

        AgentDefinition existing = agentOpt.get();

        // 2. 构建更新的 metadata（合并现有 metadata）
        Map<String, String> existingMetadata = existing.metadata() != null ? existing.metadata() : Map.of();
        Instant createdAt = extractInstant(existingMetadata, "createdAt", Instant.now());
        Map<String, String> metadata = buildUpdateMetadata(request, existingMetadata, createdAt, Instant.now());
        String preferredProviderId = request.preferredProviderId() != null
                ? normalizeOptionalText(request.preferredProviderId())
                : resolvePreferredProviderId(existing);
        String nextName = request.name() != null ? request.name().strip() : existing.name();
        String nextDescription = request.description() != null ? request.description().strip() : existing.description();
        String nextSystemPrompt = request.systemPrompt() != null ? request.systemPrompt().strip() : existing.systemPrompt();

        // 3. 构建更新的 AgentDefinition
        AgentDefinition updatedDef = AgentDefinition.builder()
                .id(id)
                .name(nextName)
                .description(nextDescription)
                .systemPrompt(nextSystemPrompt)
                .allowedTools(request.toolIds() != null ? request.toolIds() : existing.allowedTools())
                .budget(existing.budget())
                .preferredProvider(preferredProviderId)
                .source(existing.source())
                .metadata(metadata)
                .build();

        // 4. 重新注册（覆盖）
        // 对于 Builtin Agent，允许通过管理 API 在线更新，因此这里使用 forceRegister
        boolean registered;
        if (existing.source() instanceof AgentSource.Builtin && updatedDef.source() instanceof AgentSource.Builtin) {
            registered = agentRegistry.forceRegister(updatedDef);
        } else {
            registered = agentRegistry.register(updatedDef);
        }
        if (!registered) {
            log.error("Agent 更新失败: id={}", id);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Agent 更新失败");
        }

        // 5. 返回更新后的 Agent 详情
        AgentDetail detail = toAgentDetail(updatedDef);
        log.info("Agent 更新成功: id={}", id);
        return ApiResponse.ok(detail);
    }

    /**
     * 删除 Agent。
     *
     * @param id Agent ID
     * @return 204 No Content
     */
    @DeleteMapping("/{id}")
    public ApiResponse<?> deleteAgent(@PathVariable String id) {
        log.info("删除 Agent: id={}", id);

        // 1. 查找现有 Agent
        var agentOpt = agentRegistry.find(id);
        if (agentOpt.isEmpty()) {
            log.warn("Agent 不存在: id={}", id);
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Agent 不存在: id=" + id);
        }

        AgentDefinition agent = agentOpt.get();

        // 2. 检查是否为 Builtin Agent（不允许删除）
        if (agent.source() instanceof AgentSource.Builtin) {
            log.warn("不允许删除 Builtin Agent: id={}", id);
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "不允许删除内置 Agent");
        }

        // 3. 当前未维护 Agent 的反向引用索引；删除只影响后续新请求，不影响历史 trace。

        // 4. 注销 Agent
        boolean unregistered = agentRegistry.unregister(id);
        if (!unregistered) {
            log.error("Agent 删除失败: id={}", id);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Agent 删除失败");
        }

        log.info("Agent 删除成功: id={}", id);
        return ApiResponse.ok();
    }

    /**
     * 启用 Agent。
     *
     * @param id Agent ID
     * @return 204 No Content
     */
    @PostMapping("/{id}/enable")
    public ApiResponse<?> enableAgent(@PathVariable String id) {
        log.info("启用 Agent: id={}", id);

        var agentOpt = agentRegistry.find(id);
        if (agentOpt.isEmpty()) {
            log.warn("Agent 不存在: id={}", id);
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Agent 不存在: id=" + id);
        }

        AgentDefinition agent = agentOpt.get();
        Map<String, String> metadata = new HashMap<>(agent.metadata() != null ? agent.metadata() : Map.of());
        metadata.put("status", "enabled");

        AgentDefinition updatedDef = agent.toBuilder()
                .metadata(metadata)
                .build();

        agentRegistry.register(updatedDef);
        log.info("Agent 启用成功: id={}", id);
        return ApiResponse.ok();
    }

    /**
     * 禁用 Agent。
     *
     * @param id Agent ID
     * @return 204 No Content
     */
    @PostMapping("/{id}/disable")
    public ApiResponse<?> disableAgent(@PathVariable String id) {
        log.info("禁用 Agent: id={}", id);

        var agentOpt = agentRegistry.find(id);
        if (agentOpt.isEmpty()) {
            log.warn("Agent 不存在: id={}", id);
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Agent 不存在: id=" + id);
        }

        AgentDefinition agent = agentOpt.get();
        Map<String, String> metadata = new HashMap<>(agent.metadata() != null ? agent.metadata() : Map.of());
        metadata.put("status", "disabled");

        AgentDefinition updatedDef = agent.toBuilder()
                .metadata(metadata)
                .build();

        agentRegistry.register(updatedDef);
        log.info("Agent 禁用成功: id={}", id);
        return ApiResponse.ok();
    }

    /**
     * 将 AgentDefinition 转换为 AgentDetail。
     */
    private AgentDetail toAgentDetail(AgentDefinition agent) {
        // 基本信息
        String agentType = determineType(agent.source());
        String agentSource = determineSource(agent.source());
        String agentStatus = extractStatus(agent.metadata());
        boolean enabled = "enabled".equalsIgnoreCase(agentStatus);
        List<String> agentTags = extractTags(agent.metadata());
        String preferredProviderId = resolvePreferredProviderId(agent);

        // 知识库信息
        List<String> kbIds = extractKnowledgeBaseIds(agent.metadata());
        int knowledgeBaseCount = kbIds.size();
        List<AgentDetail.KnowledgeBaseInfo> knowledgeBases = kbIds.stream()
                .map(kbId -> {
                    var kbOpt = knowledgeBaseManager.getKnowledgeBase(kbId);
                    if (kbOpt.isPresent()) {
                        KnowledgeBase kb = kbOpt.get();
                        // 从 metadata 中提取知识库配置
                        Map<String, String> kbConfig = extractKnowledgeBaseConfig(agent.metadata(), kbId);
                        return new AgentDetail.KnowledgeBaseInfo(
                                kb.id(),
                                kb.name(),
                                kbConfig.containsKey("top_k") ? Integer.parseInt(kbConfig.get("top_k"))
                                : kbConfig.containsKey("topK") ? Integer.parseInt(kbConfig.get("topK")) : null,
                                kbConfig.containsKey("maxContextTokens") ? Integer.parseInt(kbConfig.get("maxContextTokens")) : null
                        );
                    }
                    return null;
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        // 模型配置
        Map<String, String> metadata = agent.metadata() != null ? agent.metadata() : Map.of();
        AgentDetail.LlmConfig llmConfig = new AgentDetail.LlmConfig(
                preferredProviderId,
                parseDouble(metadata, "temperature"),
                parseInteger(metadata, "maxTokens"),
                parseDouble(metadata, "topP")
        );

        // 时间戳
        Instant createdAt = extractInstant(metadata, "createdAt", Instant.now());
        Instant updatedAt = extractInstant(metadata, "updatedAt", Instant.now());

        // 元数据（转换为 Map<String, Object>）
        Map<String, Object> metadataObj = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : metadata.entrySet()) {
            if (!"modelId".equals(entry.getKey()) && !"preferredProviderId".equals(entry.getKey())) {
                metadataObj.put(entry.getKey(), entry.getValue());
            }
        }

        return new AgentDetail(
                agent.id(),
                agent.name(),
                agent.description(),
                agentType,
                agentSource,
                preferredProviderId,
                knowledgeBaseCount,
                updatedAt,
                createdAt,
                enabled,
                agentStatus,
                agentTags,
                agent.systemPrompt(),
                llmConfig,
                knowledgeBases,
                agent.allowedTools() != null ? agent.allowedTools() : List.of(),
                metadataObj
        );
    }

    /**
     * 从 metadata 中提取知识库 ID 列表。
     */
    private List<String> extractKnowledgeBaseIds(Map<String, String> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return List.of();
        }
        String kbIdsStr = metadata.get("knowledgeBaseIds");
        if (kbIdsStr == null || kbIdsStr.isBlank()) {
            return List.of();
        }
        List<String> kbIds = new ArrayList<>();
        if (kbIdsStr.startsWith("[")) {
            String content = kbIdsStr.substring(1, kbIdsStr.length() - 1);
            for (String id : content.split(",")) {
                String trimmed = id.trim().replaceAll("^\"|\"$", "");
                if (!trimmed.isEmpty()) {
                    kbIds.add(trimmed);
                }
            }
        } else {
            for (String id : kbIdsStr.split(",")) {
                String trimmed = id.trim();
                if (!trimmed.isEmpty()) {
                    kbIds.add(trimmed);
                }
            }
        }
        return kbIds;
    }

    /**
     * 从 metadata 中提取知识库配置。
     */
    private Map<String, String> extractKnowledgeBaseConfig(Map<String, String> metadata, String kbId) {
        Map<String, String> config = new HashMap<>();
        String prefix = "kb." + kbId + ".";
        for (Map.Entry<String, String> entry : metadata.entrySet()) {
            if (entry.getKey().startsWith(prefix)) {
                String key = entry.getKey().substring(prefix.length());
                config.put(key, entry.getValue());
            }
        }
        return config;
    }

    /**
     * 从 metadata 中提取状态。
     */
    private String extractStatus(Map<String, String> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return "enabled";
        }
        String status = metadata.get("status");
        return status != null && !status.isBlank() ? status : "enabled";
    }

    /**
     * 构建 metadata Map。
     */
    private Map<String, String> buildMetadata(CreateAgentRequest request, Instant createdAt, Instant updatedAt) {
        Map<String, String> metadata = new LinkedHashMap<>();
        
        // 时间戳
        metadata.put("createdAt", createdAt.toString());
        metadata.put("updatedAt", updatedAt.toString());
        
        // 状态
        metadata.put("status", "enabled");
        
        // 标签
        if (request.tags() != null && !request.tags().isEmpty()) {
            metadata.put("tags", String.join(",", request.tags()));
        }
        
        // 模型配置
        if (request.temperature() != null) {
            metadata.put("temperature", request.temperature().toString());
        }
        if (request.maxTokens() != null) {
            metadata.put("maxTokens", request.maxTokens().toString());
        }
        if (request.topP() != null) {
            metadata.put("topP", request.topP().toString());
        }
        
        // 知识库 ID 列表
        if (request.knowledgeBaseIds() != null && !request.knowledgeBaseIds().isEmpty()) {
            metadata.put("knowledgeBaseIds", String.join(",", request.knowledgeBaseIds()));
        }
        
        // 其他元数据
        mergeCustomMetadata(metadata, request.metadata());
        
        return metadata;
    }

    /**
     * 构建 metadata Map（更新时使用，合并现有 metadata）。
     */
    private Map<String, String> buildUpdateMetadata(UpdateAgentRequest request, 
                                                     Map<String, String> existingMetadata,
                                                     Instant createdAt, 
                                                     Instant updatedAt) {
        Map<String, String> metadata = new LinkedHashMap<>(existingMetadata);
        metadata.remove("modelId");
        metadata.remove("preferredProviderId");
        
        // 时间戳（保持不变）
        metadata.put("createdAt", createdAt.toString());
        metadata.put("updatedAt", updatedAt.toString());
        
        // 状态（保持原有状态，如果请求中没有指定）
        if (!metadata.containsKey("status")) {
            metadata.put("status", "enabled");
        }
        
        // 标签（如果请求中提供了，则更新）
        if (request.tags() != null) {
            if (request.tags().isEmpty()) {
                metadata.remove("tags");
            } else {
                metadata.put("tags", String.join(",", request.tags()));
            }
        }
        
        // 模型配置（如果请求中提供了，则更新）
        if (request.temperature() != null) {
            metadata.put("temperature", request.temperature().toString());
        }
        if (request.maxTokens() != null) {
            metadata.put("maxTokens", request.maxTokens().toString());
        }
        if (request.topP() != null) {
            metadata.put("topP", request.topP().toString());
        }
        
        // 知识库 ID 列表（如果请求中提供了，则更新）
        if (request.knowledgeBaseIds() != null) {
            metadata.keySet().removeIf(key -> key.startsWith("kb."));
            if (request.knowledgeBaseIds().isEmpty()) {
                metadata.remove("knowledgeBaseIds");
            } else {
                metadata.put("knowledgeBaseIds", String.join(",", request.knowledgeBaseIds()));
            }
        }
        
        // 其他元数据（如果请求中提供了，则更新）
        mergeCustomMetadata(metadata, request.metadata());
        
        return metadata;
    }

    @Nullable
    private String resolvePreferredProviderId(AgentDefinition agent) {
        String preferredProviderId = normalizeOptionalText(agent.preferredProvider());
        if (preferredProviderId != null) {
            return preferredProviderId;
        }

        Map<String, String> metadata = agent.metadata() != null ? agent.metadata() : Map.of();
        String metadataPreferredProviderId = normalizeOptionalText(metadata.get("preferredProviderId"));
        if (metadataPreferredProviderId != null) {
            return metadataPreferredProviderId;
        }

        return normalizeOptionalText(metadata.get("modelId"));
    }

    private String resolveSystemPrompt(String agentName,
                                       @Nullable String description,
                                       @Nullable String explicitSystemPrompt) {
        String systemPrompt = normalizeOptionalText(explicitSystemPrompt);
        if (systemPrompt != null) {
            return systemPrompt;
        }

        if (description != null) {
            return "You are " + agentName + ". Focus on this responsibility: " + description;
        }

        return "You are " + agentName + ". Help the user clearly and accurately.";
    }

    @Nullable
    private String normalizeOptionalText(@Nullable String value) {
        return SessionConfigKeys.normalizeString(value);
    }

    @Nullable
    private Integer parseInteger(Map<String, String> metadata, String key) {
        String value = normalizeOptionalText(metadata.get(key));
        if (value == null) {
            return null;
        }

        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    @Nullable
    private Double parseDouble(Map<String, String> metadata, String key) {
        String value = normalizeOptionalText(metadata.get(key));
        if (value == null) {
            return null;
        }

        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private void mergeCustomMetadata(Map<String, String> metadata, @Nullable Map<String, Object> customMetadata) {
        if (customMetadata == null || customMetadata.isEmpty()) {
            return;
        }

        for (Map.Entry<String, Object> entry : customMetadata.entrySet()) {
            if (entry.getValue() == null || isManagedMetadataKey(entry.getKey())) {
                continue;
            }
            metadata.put(entry.getKey(), entry.getValue().toString());
        }
    }

    private boolean isManagedMetadataKey(String key) {
        return "modelId".equals(key)
                || "preferredProviderId".equals(key)
                || "createdAt".equals(key)
                || "updatedAt".equals(key)
                || "status".equals(key)
                || "tags".equals(key)
                || "temperature".equals(key)
                || "maxTokens".equals(key)
                || "topP".equals(key)
                || "knowledgeBaseIds".equals(key);
    }

    // ==================== Agent Markdown 定义 ====================

    /**
     * 获取 Agent 的 Markdown 定义。
     *
     * <p>对于 MarkdownDefined 来源的 Agent，优先读取文件系统中的原始 .md 文件；
     * 对于 Builtin 和其他来源，通过序列化器动态生成。</p>
     *
     * @param id Agent ID
     * @return Markdown 文本
     */
    @GetMapping(value = "/{id}/markdown", produces = "text/markdown")
    public ApiResponse<?> getAgentMarkdown(@PathVariable String id) {
        var agentOpt = agentRegistry.find(id);
        if (agentOpt.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Agent 不存在: id=" + id);
        }

        AgentDefinition agent = agentOpt.get();

        // MarkdownDefined 来源：优先读取原始文件
        if (agent.source() instanceof AgentSource.MarkdownDefined md
                && md.filePath() != null) {
            Path filePath = Path.of(md.filePath());
            if (Files.exists(filePath)) {
                try {
                    String content = Files.readString(filePath);
                    return ApiResponse.ok(content);
                } catch (IOException e) {
                    log.warn("读取 Agent Markdown 文件失败: id={}, path={}", id, filePath);
                }
            }
        }

        // 回退：通过序列化器生成
        String markdown = markdownSerializer.serialize(agent);
        return ApiResponse.ok(markdown);
    }

    /**
     * 更新 Agent 的 Markdown 定义。
     *
     * <p>解析提交的 Markdown 内容，更新 Agent 注册并持久化到文件系统。
     * 仅支持 MarkdownDefined 来源的 Agent 和通过 API 创建的自定义 Agent。</p>
     *
     * @param id      Agent ID
     * @param content Markdown 文本
     * @return 更新后的 Agent 详情
     */
    @PutMapping(value = "/{id}/markdown", consumes = "text/plain")
    public ApiResponse<?> updateAgentMarkdown(@PathVariable String id,
                                                  @RequestBody String content) {
        var agentOpt = agentRegistry.find(id);
        if (agentOpt.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Agent 不存在: id=" + id);
        }

        AgentDefinition existing = agentOpt.get();

        // Builtin Agent 不允许通过 Markdown 更新
        if (existing.source() instanceof AgentSource.Builtin) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "内置 Agent 不支持 Markdown 编辑");
        }

        // 解析 Markdown 内容
        var parsed = markdownParser.parse(content, Path.of(id + ".md"));
        if (parsed.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Agent Markdown 解析失败，请检查格式");
        }

        AgentDefinition parsedDef = parsed.get();

        // 验证 ID 一致性
        if (!parsedDef.id().equals(id)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Markdown 中的 id 必须与路径参数一致");
        }

        try {
            // 持久化到文件系统
            String agentPath = multiAgentConfig.getAgentDefinitionsPath();
            if (agentPath.startsWith("~")) {
                agentPath = System.getProperty("user.home") + agentPath.substring(1);
            }
            Path directory = Path.of(agentPath);
            if (!Files.exists(directory)) {
                Files.createDirectories(directory);
            }
            Path filePath = directory.resolve(id + ".md");
            Files.writeString(filePath, content);

            // 通过 loader 重新加载（设置正确的 source）
            Optional<AgentDefinition> loaded = markdownLoader.loadFromFile(filePath);
            if (loaded.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Agent 更新失败，请检查定义");
            }

            agentRegistry.register(loaded.get());
            log.info("Agent Markdown 更新成功: id={}", id);

            AgentDetail detail = toAgentDetail(loaded.get());
            return ApiResponse.ok(detail);
        } catch (IOException e) {
            log.error("Agent Markdown 持久化失败: id={}, error={}", id, e.getMessage());
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "保存失败: " + e.getMessage());
        }
    }
}
