package com.lifepilot.interaction.web.controller;

import com.lifepilot.agent.model.AgentTaskMode;
import com.lifepilot.agent.model.CompletionMode;
import com.lifepilot.agent.model.CompletionReason;
import com.lifepilot.interaction.model.DeliveryMode;
import com.lifepilot.interaction.model.GatewayMessage;
import com.lifepilot.interaction.model.GatewayResponse;
import com.lifepilot.interaction.model.ResponseContent;
import com.lifepilot.interaction.service.ChannelIngressService;
import com.lifepilot.interaction.web.model.*;
import com.lifepilot.interaction.web.repository.AttachmentRepository;
import com.lifepilot.interaction.web.repository.MessageFeedbackRepository;
import com.lifepilot.interaction.web.sse.SseEventType;
import com.lifepilot.interaction.web.sse.SseSessionManager;
import com.lifepilot.interaction.web.service.BrowserIngressService;
import com.lifepilot.knowledge.config.KnowledgeBaseProperties;
import com.lifepilot.media.audio.SpeechSynthesizer;
import com.lifepilot.media.config.MediaProperties;
import com.lifepilot.memory.feedback.FeedbackProcessor;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * 对话 REST + SSE 端点，处理消息发送、会话管理和 A2UI 信号回传。
 *
 * <p>浏览器请求先由 {@link BrowserIngressService} 组装为统一网关消息，
 * 再通过 {@link ChannelIngressService} 进入主执行链路。</p>
 *
 * @author zsg
 * @since 2026-02-26
 */
@RestController
@RequestMapping("/api/chat")
@ConditionalOnProperty(name = "lifepilot.gateway.channels.web.enabled", havingValue = "true")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);
    private static final Executor VIRTUAL_EXECUTOR = command -> Thread.ofVirtual().start(command);

    /**
     * 支持的文件扩展名。
     *
     * <p>Phase 2：图片 / 文档；Phase 3：补充常见音频格式，用于语音消息与简单播放器。</p>
     */
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            // 图片
            ".jpg", ".jpeg", ".png", ".gif", ".webp", ".bmp",
            // PDF
            ".pdf",
            // 文本
            ".txt", ".md", ".csv",
            // Office
            ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx",
            // 音频（Phase 3）
            ".mp3", ".wav", ".m4a", ".aac", ".ogg", ".flac", ".webm",
            // 视频（multimodal-completion）
            ".mp4", ".avi", ".mov", ".mkv", ".flv"
    );

    private final BrowserIngressService browserIngressService;
    private final ChannelIngressService channelIngressService;
    private final SseSessionManager sseManager;
    private final com.lifepilot.interaction.web.service.ChatSessionService sessionService;
    private final MessageFeedbackRepository feedbackRepository;
    private final AttachmentRepository attachmentRepository;
    private final KnowledgeBaseProperties knowledgeBaseProperties;
    @Nullable
    private final FeedbackProcessor feedbackProcessor;
    @Nullable
    private final SpeechSynthesizer speechSynthesizer;
    private final MediaProperties mediaProperties;

    public ChatController(BrowserIngressService browserIngressService,
                          ChannelIngressService channelIngressService,
                          SseSessionManager sseManager,
                          com.lifepilot.interaction.web.service.ChatSessionService sessionService,
                          MessageFeedbackRepository feedbackRepository,
                          AttachmentRepository attachmentRepository,
                          KnowledgeBaseProperties knowledgeBaseProperties,
                          @Nullable FeedbackProcessor feedbackProcessor,
                          @Nullable SpeechSynthesizer speechSynthesizer,
                          MediaProperties mediaProperties) {
        this.browserIngressService = browserIngressService;
        this.channelIngressService = channelIngressService;
        this.sseManager = sseManager;
        this.sessionService = sessionService;
        this.feedbackRepository = feedbackRepository;
        this.attachmentRepository = attachmentRepository;
        this.knowledgeBaseProperties = knowledgeBaseProperties;
        this.feedbackProcessor = feedbackProcessor;
        this.speechSynthesizer = speechSynthesizer;
        this.mediaProperties = mediaProperties;
    }

    private boolean hasContentOrAttachments(ChatRequest request) {
        return request.action() != ChatTurnAction.SEND || request.hasMessagePayload();
    }

    /**
     * 查询语音输入能力（原生音频 Provider / STT 转录）。
     *
     * @return nativeAudio、stt、supported 三个布尔值
     */
    @GetMapping("/voice-capability")
    public ApiResponse<BrowserIngressService.VoiceCapability> getVoiceCapability() {
        return ApiResponse.ok(browserIngressService.voiceCapability());
    }

    /**
     * 非流式发送消息。
     *
     * <p>将消息提交到 MessageGateway 中间件管道，同步等待响应后返回完整结果。
     *
     * @param request     聊天请求（消息内容和附件至少一项存在）
     * @param httpRequest HTTP 请求
     * @return 包含 entryId、content、a2ui、tokenUsage 的响应
     */
    @PostMapping("/messages")
    public ResponseEntity<?> sendMessage(@RequestBody ChatRequest request,
                                         HttpServletRequest httpRequest) {
        if (!hasContentOrAttachments(request)) {
            log.warn("非流式消息请求内容为空，且没有附件");
            return ResponseEntity.badRequest().body(
                    new ErrorResponse(400, "消息内容和附件不能同时为空", Instant.now()));
        }

        log.debug("收到非流式消息请求: sessionId={}", request.sessionId());
        GatewayMessage message = browserIngressService.buildChatMessage(request, httpRequest, DeliveryMode.SYNC);
        var response = channelIngressService.submitSync(message);
        if (!response.isSuccess()) {
            return ResponseEntity.status(response.statusCode()).body(
                    new ErrorResponse(response.statusCode(), response.errorMessage(), Instant.now()));
        }
        var chatResponse = toChatResponse(response);
        return ResponseEntity.ok(chatResponse);
    }

    /**
     * SSE 流式发送消息。
     *
     * <p>将消息提交到 MessageGateway，若响应为 {@link ResponseContent.StreamingContent}
     * 则通过 {@link SseSessionManager} 创建 SseEmitter 返回给客户端；
     * 否则创建临时 SseEmitter 发送 done 事件后立即完成。
     *
     * @param request     聊天请求（消息内容和附件至少一项存在）
     * @param httpRequest HTTP 请求
     * @return SseEmitter 用于流式推送事件
     */
    @PostMapping("/messages/stream")
    public SseEmitter sendMessageStream(@RequestBody ChatRequest request,
                                         HttpServletRequest httpRequest) {
        if (!hasContentOrAttachments(request)) {
            log.warn("流式消息请求内容为空，且没有附件");
            var emitter = new SseEmitter(0L);
            try {
                var errorEvent = SseEmitter.event()
                        .name(SseEventType.ERROR)
                        .data(Map.of("code", 400, "message", "消息内容和附件不能同时为空"));
                emitter.send(errorEvent);
                emitter.complete();
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
            return emitter;
        }

        log.debug("收到流式消息请求: sessionId={}", request.sessionId());
        GatewayMessage message = browserIngressService.buildChatMessage(request, httpRequest, DeliveryMode.SSE_STREAM);
        var response = channelIngressService.submitSync(message);

        if (response.content() instanceof ResponseContent.StreamingContent(String streamId)) {
            // 流式响应：emitter 已在 ExecutionMiddleware 中预创建，直接获取
            var emitter = sseManager.getEmitter(streamId);
            if (emitter != null) {
                log.debug("获取已注册 SSE 流: streamId={}", streamId);
                return emitter;
            }
            // 兜底：极端情况下 emitter 未注册（不应发生），降级创建
            log.warn("SSE 流未预注册，降级创建: streamId={}", streamId);
            return sseManager.createEmitter(streamId);
        }

        // 非流式响应：创建临时 SseEmitter
        var emitter = new SseEmitter(0L);
        try {
            if (!response.isSuccess()) {
                // 错误响应（中间件拦截等）：发送 error 事件，避免前端误判为成功
                log.debug("流式端点收到错误响应: statusCode={}", response.statusCode());
                var errorData = new java.util.HashMap<String, Object>();
                errorData.put("code", response.statusCode());
                errorData.put("message", response.errorMessage() != null
                        ? response.errorMessage() : "请求处理失败");
                var errorEvent = SseEmitter.event()
                        .name(SseEventType.ERROR)
                        .data(errorData);
                emitter.send(errorEvent);
            } else {
                // 成功的非流式响应：发送 done 事件
                log.debug("流式端点收到非流式响应，发送 done 事件后关闭");
                var chatResponse = toChatResponse(response);
                var doneDataBuilder = new java.util.HashMap<String, Object>();
                doneDataBuilder.put("entryId", chatResponse.entryId());
                doneDataBuilder.put("content", chatResponse.content());
                if (chatResponse.turnId() != null) {
                    doneDataBuilder.put("turnId", chatResponse.turnId());
                }
                if (request.sessionId() != null) {
                    doneDataBuilder.put("sessionId", request.sessionId());
                }
                if (response.tokenUsage() != null) {
                    doneDataBuilder.put("tokenUsage", response.tokenUsage());
                }
                if (chatResponse.traceId() != null) {
                    doneDataBuilder.put("traceId", chatResponse.traceId());
                }
                doneDataBuilder.put("completionMode", chatResponse.completionMode().name());
                if (chatResponse.turnStatus() != null) {
                    doneDataBuilder.put("turnStatus", chatResponse.turnStatus().name());
                }
                if (chatResponse.resumedFromTraceId() != null) {
                    doneDataBuilder.put("resumedFromTraceId", chatResponse.resumedFromTraceId());
                }
                if (chatResponse.a2uiComponents() != null && !chatResponse.a2uiComponents().isEmpty()) {
                    doneDataBuilder.put("a2uiComponents", chatResponse.a2uiComponents());
                }
                doneDataBuilder.put("timestamp", Instant.now().toEpochMilli());
                var doneEvent = SseEmitter.event()
                        .name(SseEventType.DONE)
                        .data(doneDataBuilder);
                emitter.send(doneEvent);
            }
            emitter.complete();
        } catch (Exception e) {
            log.warn("发送 SSE 事件失败", e);
            emitter.completeWithError(e);
        }
        return emitter;
    }

    /**
     * 创建新会话。
     *
     * @param request 创建会话请求（title 可选）
     * @return 创建的会话信息（201 Created）
     */
    @PostMapping("/sessions")
    public ResponseEntity<?> createSession(@RequestBody CreateSessionRequest request) {
        log.debug("创建会话: title={}, projectId={}", request.title(), request.projectId());
        try {
            var session = sessionService.createSession(request.title(), request.projectId());
            // 转换为 SessionInfo
            var sessionInfo = new SessionInfo(
                    session.id(),
                    session.title(),
                    session.createdAt(),
                    session.updatedAt(),
                    session.isPinned(),
                    session.archived(),
                    session.summary(),
                    session.lastMessageAt()
            );
            return ResponseEntity.status(201).body(sessionInfo);
        } catch (Exception e) {
            log.error("创建会话时发生错误", e);
            return ResponseEntity.internalServerError().body(
                    new ErrorResponse(500, "创建会话失败: " + e.getMessage(), Instant.now()));
        }
    }

    /**
     * 获取会话列表。
     *
     * @param q        关键词搜索（名称或最近消息内容）
     * @param pinned   过滤置顶状态（true/false）
     * @param archived 过滤归档状态（true/false）
     * @param timeRange 时间范围（7d/30d）
     * @param sortBy   排序字段（updatedAt/lastMessageAt）
     * @param order   排序方向（asc/desc）
     * @return 会话摘要列表
     */
    @GetMapping("/sessions")
    public ResponseEntity<List<SessionInfo>> listSessions(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Boolean pinned,
            @RequestParam(required = false) Boolean archived,
            @RequestParam(required = false) String timeRange,
            @RequestParam(defaultValue = "updatedAt") String sortBy,
            @RequestParam(defaultValue = "desc") String order
    ) {
        log.debug("获取会话列表: q={}, pinned={}, archived={}, timeRange={}, sortBy={}, order={}",
                q, pinned, archived, timeRange, sortBy, order);
        var sessions = sessionService.listSessions(q, pinned, archived, timeRange, sortBy, order);
        return ResponseEntity.ok(sessions);
    }

    /**
     * 更新指定会话。
     *
     * <p>支持更新会话标题、置顶状态和归档状态。</p>
     *
     * @param id      会话 ID
     * @param request 更新请求（title、pinned、archived 均为可选）
     * @return 更新后的会话信息
     */
    @PatchMapping("/sessions/{id}")
    public ResponseEntity<?> updateSession(
            @PathVariable String id,
            @RequestBody UpdateSessionRequest request) {
        log.debug("更新会话: sessionId={}, title={}, pinned={}, archived={}",
                id, request.title(), request.pinned(), request.archived());
        try {
            var sessionInfo = sessionService.updateSession(
                    id, request.title(), request.pinned(), request.archived());
            return ResponseEntity.ok(sessionInfo);
        } catch (IllegalArgumentException e) {
            log.warn("更新会话失败: {}", e.getMessage());
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            log.error("更新会话时发生错误: sessionId={}", id, e);
            return ResponseEntity.badRequest().body(
                    new ErrorResponse(400, "更新会话失败: " + e.getMessage(), Instant.now()));
        }
    }

    /**
     * 获取会话详情。
     *
     * <p>返回会话的完整信息，包括基础信息、关联知识库、消息统计等。</p>
     *
     * @param id 会话 ID
     * @return 会话详情
     */
    @GetMapping("/sessions/{id}")
    public ResponseEntity<?> getSession(@PathVariable String id) {
        log.debug("获取会话详情: sessionId={}", id);
        try {
            var sessionDetail = sessionService.getSessionDetail(id);
            return ResponseEntity.ok(sessionDetail);
        } catch (IllegalArgumentException e) {
            log.warn("获取会话详情失败: {}", e.getMessage());
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            log.error("获取会话详情时发生错误: sessionId={}", id, e);
            return ResponseEntity.internalServerError().body(
                    new ErrorResponse(500, "获取会话详情失败: " + e.getMessage(), Instant.now()));
        }
    }

    /**
     * 获取指定会话的历史消息。
     *
     * <p>从 EpisodicMemory（L2 情景记忆）中查询该会话的所有消息记录。
     * 如果记忆系统未启用（lifepilot.memory.enabled=false），则返回空列表。</p>
     *
     * @param id 会话 ID
     * @return 消息摘要列表（按时间顺序）
     */
    @GetMapping("/sessions/{id}/messages")
    public ResponseEntity<List<MessageInfo>> getSessionMessages(@PathVariable String id) {
        log.debug("获取会话历史消息: sessionId={}", id);
        try {
            var messages = sessionService.getSessionMessages(id);
            return ResponseEntity.ok(messages);
        } catch (IllegalArgumentException e) {
            log.warn("获取会话历史消息失败: {}", e.getMessage());
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            log.error("获取会话历史消息时发生错误: sessionId={}", id, e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * 删除指定会话。
     *
     * @param id 会话 ID
     * @return 204 No Content
     */
    @DeleteMapping("/sessions/{id}")
    public ResponseEntity<Void> deleteSession(@PathVariable String id) {
        log.debug("删除会话: sessionId={}", id);
        try {
            sessionService.deleteSession(id);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException e) {
            log.warn("删除会话失败: {}", e.getMessage());
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            log.error("删除会话时发生错误: sessionId={}", id, e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * 批量操作会话。
     *
     * <p>支持批量置顶、取消置顶、归档、取消归档、删除操作。</p>
     *
     * @param request 批量操作请求
     * @return 204 No Content
     */
    @PostMapping("/sessions/batch")
    public ResponseEntity<?> batchUpdateSessions(@RequestBody BatchUpdateRequest request) {
        log.debug("批量操作会话: action={}, sessionIds={}", request.action(), request.sessionIds());
        
        if (request.action() == null || request.action().isBlank()) {
            log.warn("批量操作失败: 操作类型为空");
            return ResponseEntity.badRequest().body(
                    new ErrorResponse(400, "操作类型不能为空", Instant.now()));
        }

        if (request.sessionIds() == null || request.sessionIds().isEmpty()) {
            log.warn("批量操作失败: 会话 ID 列表为空");
            return ResponseEntity.badRequest().body(
                    new ErrorResponse(400, "会话 ID 列表不能为空", Instant.now()));
        }

        try {
            int count = sessionService.batchUpdateSessions(request.action(), request.sessionIds());
            log.info("批量操作完成: action={}, count={}", request.action(), count);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException e) {
            log.warn("批量操作失败: {}", e.getMessage());
            return ResponseEntity.badRequest().body(
                    new ErrorResponse(400, e.getMessage(), Instant.now()));
        } catch (Exception e) {
            log.error("批量操作时发生错误: action={}", request.action(), e);
            return ResponseEntity.internalServerError().body(
                    new ErrorResponse(500, "批量操作失败: " + e.getMessage(), Instant.now()));
        }
    }

    /**
     * 分叉会话：从指定消息创建新会话，复制上下文。
     *
     * <p>从指定消息开始，复制该消息及其之前的所有消息到新会话中。</p>
     *
     * @param id      原会话 ID
     * @param request 分叉请求（fromEntryId 必填，title 可选）
     * @return 新创建的会话信息
     */
    @PostMapping("/sessions/{id}/fork")
    public ResponseEntity<?> forkSession(
            @PathVariable String id,
            @RequestBody ForkSessionRequest request) {
        log.debug("分叉会话: sessionId={}, fromEntryId={}, title={}",
                id, request.fromEntryId(), request.title());
        
        if (request.fromEntryId() == null || request.fromEntryId().isBlank()) {
            log.warn("分叉会话失败: 起始消息 ID 为空");
            return ResponseEntity.badRequest().body(
                    new ErrorResponse(400, "起始消息 ID 不能为空", Instant.now()));
        }

        try {
            SessionInfo newSession = sessionService.forkSession(id, request.fromEntryId(), request.title());
            log.info("会话分叉成功: originalSessionId={}, newSessionId={}", id, newSession.id());
            return ResponseEntity.ok(newSession);
        } catch (IllegalArgumentException e) {
            log.warn("分叉会话失败: {}", e.getMessage());
            return ResponseEntity.badRequest().body(
                    new ErrorResponse(400, e.getMessage(), Instant.now()));
        } catch (Exception e) {
            log.error("分叉会话时发生错误: sessionId={}", id, e);
            return ResponseEntity.internalServerError().body(
                    new ErrorResponse(500, "分叉会话失败: " + e.getMessage(), Instant.now()));
        }
    }

    /**
     * A2UI 信号回传。
     *
     * <p>将用户与 A2UI 组件的交互信号转换为 GatewayMessage 提交到中间件管道处理。
     *
     * @param request     信号请求（name 和 sessionId 不可为空）
     * @param httpRequest HTTP 请求
     * @return Agent 处理信号后的响应
     */
    @PostMapping("/signals")
    public ResponseEntity<?> handleSignal(@RequestBody SignalRequest request,
                                           HttpServletRequest httpRequest) {
        if (request.name() == null || request.name().isBlank()) {
            log.warn("信号名称为空");
            return ResponseEntity.badRequest().body(
                    new ErrorResponse(400, "信号名称不能为空", Instant.now()));
        }
        if (request.sessionId() == null || request.sessionId().isBlank()) {
            log.warn("信号会话 ID 为空");
            return ResponseEntity.badRequest().body(
                    new ErrorResponse(400, "会话 ID 不能为空", Instant.now()));
        }

        log.debug("收到 A2UI 信号: name={}, sessionId={}", request.name(), request.sessionId());
        GatewayMessage message = browserIngressService.buildSignalMessage(request, httpRequest);
        var response = channelIngressService.submitSync(message);
        if (!response.isSuccess()) {
            return ResponseEntity.status(response.statusCode()).body(
                    new ErrorResponse(response.statusCode(), response.errorMessage(), Instant.now()));
        }
        return ResponseEntity.ok(toChatResponse(response));
    }

    /**
     * 上传消息附件。
     *
     * <p>支持上传图片、PDF、文本文件等附件。文件保存到本地存储，并返回文件信息。</p>
     *
     * @param file      上传的文件
     * @param sessionId 会话 ID（可选，如果提供则验证会话存在）
     * @return 附件上传响应（包含 fileId、url、filename、size、type）
     */
    @PostMapping("/messages/upload")
    public ResponseEntity<?> uploadAttachment(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "sessionId", required = false) String sessionId) {
        log.debug("上传附件: fileName={}, size={}, sessionId={}", 
                file.getOriginalFilename(), file.getSize(), sessionId);

        // 验证文件
        if (file.isEmpty()) {
            log.warn("附件上传失败: 文件为空");
            return ResponseEntity.badRequest().body(
                    new ErrorResponse(400, "文件不能为空", Instant.now()));
        }

        String originalName = file.getOriginalFilename();
        if (originalName == null || originalName.isBlank()) {
            log.warn("附件上传失败: 文件名为空");
            return ResponseEntity.badRequest().body(
                    new ErrorResponse(400, "文件名不能为空", Instant.now()));
        }

        // 验证文件扩展名
        String extension = getFileExtension(originalName);
        if (extension == null || !ALLOWED_EXTENSIONS.contains(extension.toLowerCase())) {
            log.warn("附件上传失败: 不支持的文件格式: {}", originalName);
            return ResponseEntity.badRequest().body(
                    new ErrorResponse(400, "不支持的文件格式，仅支持图片、PDF、文本、Office文档、音频和视频", Instant.now()));
        }

        // 验证文件大小
        long maxFileSize = knowledgeBaseProperties.maxFileSize();
        if (file.getSize() > maxFileSize) {
            log.warn("附件上传失败: 文件大小超过限制: size={}, max={}", file.getSize(), maxFileSize);
            return ResponseEntity.badRequest().body(
                    new ErrorResponse(400, "文件大小超过限制（最大 " + (maxFileSize / 1024 / 1024) + "MB）", Instant.now()));
        }

        // 验证 sessionId 必须提供且会话存在（session_id 有外键约束，不能为空）
        if (sessionId == null || sessionId.isBlank()) {
            log.warn("附件上传失败: 未提供 sessionId");
            return ResponseEntity.badRequest().body(
                    new ErrorResponse(400, "sessionId 不能为空", Instant.now()));
        }
        if (!attachmentRepository.sessionExists(sessionId)) {
            log.warn("附件上传失败: 会话不存在: sessionId={}", sessionId);
            return ResponseEntity.badRequest().body(
                    new ErrorResponse(400, "会话不存在", Instant.now()));
        }

        try {
            // 保存文件到本地存储
            Path dataDir = Paths.get(knowledgeBaseProperties.dataDir());
            Path attachmentsDir = dataDir.resolve("attachments");
            Files.createDirectories(attachmentsDir);

            // 生成唯一文件名：UUID + 原始文件名
            String fileId = UUID.randomUUID().toString();
            String storedFileName = fileId + "_" + originalName;
            Path filePath = attachmentsDir.resolve(storedFileName);

            // 保存文件
            file.transferTo(filePath.toFile());
            log.debug("文件保存成功: path={}", filePath);

            // 检测 MIME 类型
            String mimeType = file.getContentType();
            if (mimeType == null || mimeType.isBlank()) {
                mimeType = detectMimeType(originalName);
            }

            // 生成文件访问 URL（使用数据库附件 ID，与 AttachmentController 查询一致）
            String attachmentId = attachmentRepository.saveForEntry(
                    null,  // entryId
                    sessionId,
                    originalName,
                    filePath.toString(),
                    file.getSize(),
                    mimeType,
                    null  // url 稍后设置
            );

            String url = "/api/attachments/" + attachmentId;

            log.info("附件上传成功: fileId={}, fileName={}, size={}", attachmentId, originalName, file.getSize());

            // 返回上传响应
            UploadResponse response = new UploadResponse(
                    attachmentId,
                    url,
                    originalName,
                    file.getSize(),
                    mimeType
            );
            return ResponseEntity.ok(response);

        } catch (IOException e) {
            log.error("附件上传失败: fileName={}", originalName, e);
            return ResponseEntity.internalServerError().body(
                    new ErrorResponse(500, "文件保存失败: " + e.getMessage(), Instant.now()));
        } catch (Exception e) {
            log.error("附件上传时发生错误: fileName={}", originalName, e);
            return ResponseEntity.internalServerError().body(
                    new ErrorResponse(500, "附件上传失败: " + e.getMessage(), Instant.now()));
        }
    }

    /**
     * 更新会话配置。
     *
     * <p>支持更新模型、温度、三维预算和关联的知识库列表。</p>
     *
     * @param id      会话 ID
     * @param request 配置更新请求
     * @return 204 No Content
     */
    @PatchMapping("/sessions/{id}/config")
    public ResponseEntity<?> updateSessionConfig(
            @PathVariable String id,
            @RequestBody SessionConfigRequest request) {
        log.debug("更新会话配置: sessionId={}, preferredProviderId={}, temperature={}, maxSteps={}, maxDurationSeconds={}, knowledgeBaseIds={}, datastoreIds={}",
                id, request.preferredProviderId(), request.temperature(),
                request.maxSteps(), request.maxDurationSeconds(), request.knowledgeBaseIds(), request.datastoreIds());
        
        try {
            sessionService.updateSessionConfig(id, request);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException e) {
            log.warn("更新会话配置失败: {}", e.getMessage());
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            log.error("更新会话配置时发生错误: sessionId={}", id, e);
            return ResponseEntity.badRequest().body(
                    new ErrorResponse(400, "更新会话配置失败: " + e.getMessage(), Instant.now()));
        }
    }

    /**
     * 提交消息反馈。
     *
     * <p>支持对消息进行点赞或点踩反馈。点踩时需要提供反馈内容。</p>
     *
     * @param entryId transcript 条目 ID
     * @param request   反馈请求（type 必填，feedback 在 type='dislike' 时必填）
     * @return 204 No Content
     */
    @PostMapping("/entries/{entryId}/feedback")
    public ResponseEntity<?> submitFeedback(
            @PathVariable String entryId,
            @RequestBody FeedbackRequest request) {
        log.debug("提交条目反馈: entryId={}, type={}", entryId, request.type());

        // 验证反馈类型
        if (request.type() == null || request.type().isBlank()) {
            log.warn("消息反馈失败: 反馈类型为空");
            return ResponseEntity.badRequest().body(
                    new ErrorResponse(400, "反馈类型不能为空", Instant.now()));
        }

        if (!"like".equals(request.type()) && !"dislike".equals(request.type())) {
            log.warn("消息反馈失败: 无效的反馈类型: {}", request.type());
            return ResponseEntity.badRequest().body(
                    new ErrorResponse(400, "反馈类型必须是 'like' 或 'dislike'", Instant.now()));
        }

        // 从 transcript 读模型获取会话 ID
        String sessionId = feedbackRepository.getSessionIdByEntryId(entryId);
        if (sessionId == null) {
            log.warn("条目反馈失败: 条目不存在: entryId={}", entryId);
            return ResponseEntity.badRequest().body(
                    new ErrorResponse(400, "消息不存在", Instant.now()));
        }

        try {
            // 保存反馈
            feedbackRepository.saveForEntry(entryId, sessionId, request.type(), request.feedback());
            log.info("条目反馈保存成功: entryId={}, type={}", entryId, request.type());

            // 异步调用 FeedbackProcessor 调整关联实体 importanceScore
            if (feedbackProcessor != null) {
                CompletableFuture.runAsync(() -> {
                    try {
                        feedbackProcessor.processFeedbackForEntry(entryId, request.type());
                    } catch (Exception ex) {
                        log.warn("反馈处理失败: entryId={}, error={}", entryId, ex.getMessage());
                    }
                }, VIRTUAL_EXECUTOR);
            }

            return ResponseEntity.noContent().build();
        } catch (Exception e) {
            log.error("保存条目反馈时发生错误: entryId={}", entryId, e);
            return ResponseEntity.internalServerError().body(
                    new ErrorResponse(500, "保存反馈失败: " + e.getMessage(), Instant.now()));
        }
    }

    // ── 内部辅助方法 ──────────────────────────────────────────

    /**
     * TTS 语音合成端点。
     *
     * <p>将指定消息的文本内容通过 {@link SpeechSynthesizer} 合成为音频，
     * 返回 {@code audio/mpeg} 格式的二进制流。支持通过查询参数覆盖默认语音风格和语速。</p>
     *
     * @param entryId transcript 条目 ID
     * @param voice     语音风格（可选，覆盖默认配置）
     * @param speed     语速倍率（可选，覆盖默认配置）
     * @return 音频二进制流
     */
    @PostMapping("/entries/{entryId}/tts")
    public ResponseEntity<?> synthesizeSpeech(
            @PathVariable String entryId,
            @RequestParam(required = false) String voice,
            @RequestParam(required = false) Double speed) {

        // 检查 TTS 是否启用
        if (!mediaProperties.getTts().isEnabled()) {
            log.debug("TTS 端点已禁用: tts.enabled=false");
            return ResponseEntity.status(503).body(
                    new ErrorResponse(503, "语音合成服务已禁用", Instant.now()));
        }

        // 检查 SpeechSynthesizer 是否注入
        if (speechSynthesizer == null) {
            log.debug("TTS 端点不可用: SpeechSynthesizer 未注入");
            return ResponseEntity.status(503).body(
                    new ErrorResponse(503, "语音合成服务不可用，未配置 TTS Provider", Instant.now()));
        }

        // 获取消息文本
        String content = feedbackRepository.getEntryContentById(entryId);
        if (content == null || content.isBlank()) {
            log.warn("TTS 合成失败: 条目不存在或内容为空: entryId={}", entryId);
            return ResponseEntity.notFound().build();
        }

        try {
            byte[] audioData = speechSynthesizer.synthesize(content);
            log.info("TTS 合成成功: entryId={}, audioSize={}", entryId, audioData.length);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_TYPE, "audio/mpeg")
                    .body(audioData);
        } catch (Exception e) {
            log.error("TTS 合成失败: entryId={}", entryId, e);
            return ResponseEntity.internalServerError().body(
                    new ErrorResponse(500, "语音合成失败: " + e.getMessage(), Instant.now()));
        }
    }

    // ── 内部辅助方法（续）──────────────────────────────────────

    /**
     * 获取文件扩展名（包含点号）。
     *
     * @param fileName 文件名
     * @return 扩展名（如 ".pdf"），如果没有扩展名返回 null
     */
    private String getFileExtension(String fileName) {
        int lastDot = fileName.lastIndexOf('.');
        if (lastDot == -1 || lastDot == fileName.length() - 1) {
            return null;
        }
        return fileName.substring(lastDot);
    }

    /**
     * 根据文件名检测 MIME 类型。
     *
     * @param fileName 文件名
     * @return MIME 类型
     */
    private String detectMimeType(String fileName) {
        String extension = getFileExtension(fileName);
        if (extension == null) {
            return "application/octet-stream";
        }
        extension = extension.toLowerCase();
        return switch (extension) {
            case ".jpg", ".jpeg" -> "image/jpeg";
            case ".png" -> "image/png";
            case ".gif" -> "image/gif";
            case ".webp" -> "image/webp";
            case ".bmp" -> "image/bmp";
            case ".pdf" -> "application/pdf";
            case ".txt" -> "text/plain";
            case ".md" -> "text/markdown";
            case ".csv" -> "text/csv";
            case ".doc" -> "application/msword";
            case ".docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case ".xls" -> "application/vnd.ms-excel";
            case ".xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case ".ppt" -> "application/vnd.ms-powerpoint";
            case ".pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation";
            // 音频 MIME 类型：优先复用上传时设置的 contentType，此处仅兜底
            case ".mp3" -> "audio/mpeg";
            case ".wav" -> "audio/wav";
            case ".m4a" -> "audio/mp4";
            case ".aac" -> "audio/aac";
            case ".ogg" -> "audio/ogg";
            case ".flac" -> "audio/flac";
            case ".webm" -> "audio/webm";
            // 视频 MIME 类型（multimodal-completion）
            case ".mp4" -> "video/mp4";
            case ".avi" -> "video/x-msvideo";
            case ".mov" -> "video/quicktime";
            case ".mkv" -> "video/x-matroska";
            case ".flv" -> "video/x-flv";
            default -> "application/octet-stream";
        };
    }

    /**
     * 将 GatewayResponse 转换为 ChatResponse。
     *
     * @param response 网关响应
     * @return 对话响应
     */
    private ChatResponse toChatResponse(GatewayResponse response) {
        var text = switch (response.content()) {
            case ResponseContent.TextContent tc -> tc.text();
            case ResponseContent.MarkdownContent mc -> mc.markdown();
            case ResponseContent.CardContent cc -> cc.toPlainText();
            case ResponseContent.ImageContent img -> img.toPlainText();
            case ResponseContent.FileContent f -> f.toPlainText();
            case ResponseContent.StreamingContent sc -> sc.toPlainText();
            case null -> "";
        };
        @SuppressWarnings("unchecked")
        List<A2uiComponent> a2uiComponents = response.metadata() != null
                ? (List<A2uiComponent>) response.metadata().get("a2uiComponents")
                : null;
        String traceId = response.metadata() != null
                ? (String) response.metadata().get("traceId")
                : null;
        String turnId = response.metadata() != null
                ? (String) response.metadata().get("turnId")
                : null;
        AgentTaskMode taskMode = response.metadata() != null
                ? parseTaskMode(response.metadata().get("taskMode"))
                : AgentTaskMode.AUTO;
        CompletionMode completionMode = response.metadata() != null
                ? parseCompletionMode(response.metadata().get("completionMode"))
                : CompletionMode.NORMAL;
        CompletionReason completionReason = response.metadata() != null
                ? parseCompletionReason(response.metadata().get("completionReason"))
                : null;
        ChatTurnStatus turnStatus = response.metadata() != null
                ? parseTurnStatus(response.metadata().get("turnStatus"))
                : ChatTurnStatus.SUCCESS;
        String resumedFromTraceId = response.metadata() != null
                ? (String) response.metadata().get("resumedFromTraceId")
                : null;
        return new ChatResponse(
                response.responseId(),
                turnId,
                taskMode,
                text,
                a2uiComponents,
                response.tokenUsage(),
                traceId,
                completionMode,
                completionReason,
                resumedFromTraceId,
                turnStatus
        );
    }

    private AgentTaskMode parseTaskMode(@Nullable Object rawValue) {
        if (rawValue instanceof AgentTaskMode taskMode) {
            return taskMode;
        }
        if (rawValue instanceof String rawText) {
            try {
                return AgentTaskMode.valueOf(rawText);
            } catch (IllegalArgumentException ignored) {
                return AgentTaskMode.AUTO;
            }
        }
        return AgentTaskMode.AUTO;
    }

    private CompletionMode parseCompletionMode(@Nullable Object rawValue) {
        if (rawValue instanceof CompletionMode completionMode) {
            return completionMode;
        }
        if (rawValue instanceof String rawText) {
            try {
                return CompletionMode.valueOf(rawText);
            } catch (IllegalArgumentException ignored) {
                return CompletionMode.NORMAL;
            }
        }
        return CompletionMode.NORMAL;
    }

    private @Nullable CompletionReason parseCompletionReason(@Nullable Object rawValue) {
        if (rawValue instanceof CompletionReason completionReason) {
            return completionReason;
        }
        if (rawValue instanceof String rawText) {
            try {
                return CompletionReason.valueOf(rawText);
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
        return null;
    }

    private ChatTurnStatus parseTurnStatus(@Nullable Object rawValue) {
        if (rawValue instanceof ChatTurnStatus turnStatus) {
            return turnStatus;
        }
        if (rawValue instanceof String rawText) {
            try {
                return ChatTurnStatus.valueOf(rawText);
            } catch (IllegalArgumentException ignored) {
                return ChatTurnStatus.SUCCESS;
            }
        }
        return ChatTurnStatus.SUCCESS;
    }
}
