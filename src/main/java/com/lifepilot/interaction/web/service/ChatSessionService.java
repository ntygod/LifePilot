package com.lifepilot.interaction.web.service;

import com.lifepilot.agent.session.SessionManager;
import com.lifepilot.interaction.web.model.ChatSession;
import com.lifepilot.interaction.web.model.MessageInfo;
import com.lifepilot.interaction.web.model.SessionConfigRequest;
import com.lifepilot.interaction.web.model.SessionDetailInfo;
import com.lifepilot.interaction.web.model.SessionInfo;
import com.lifepilot.interaction.web.repository.ChatMessageRepository;
import com.lifepilot.interaction.web.repository.ChatSessionRepository;
import com.lifepilot.interaction.web.repository.SessionKnowledgeBaseRepository;
import com.lifepilot.memory.episodic.EpisodicMemory;
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Web UI 会话管理服务。
 *
 * <p>提供会话的创建、查询、更新、删除等业务逻辑。
 *
 * @author zsg
 * @since 2026-02-27
 */
@Service
public class ChatSessionService {

    private static final Logger log = LoggerFactory.getLogger(ChatSessionService.class);

    private final ChatSessionRepository sessionRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final SessionKnowledgeBaseRepository sessionKnowledgeBaseRepository;
    @Nullable
    private final SessionManager sessionManager;
    @Nullable
    private final EpisodicMemory episodicMemory;

    public ChatSessionService(ChatSessionRepository sessionRepository,
                              ChatMessageRepository chatMessageRepository,
                              SessionKnowledgeBaseRepository sessionKnowledgeBaseRepository,
                              @Nullable EpisodicMemory episodicMemory,
                              @Nullable SessionManager sessionManager) {
        this.sessionRepository = sessionRepository;
        this.chatMessageRepository = chatMessageRepository;
        this.sessionKnowledgeBaseRepository = sessionKnowledgeBaseRepository;
        this.episodicMemory = episodicMemory;
        this.sessionManager = sessionManager;
    }

    /**
     * 创建新会话。
     *
     * @param title 会话标题（可选）
     * @return 创建的会话
     */
    @Transactional
    public ChatSession createSession(String title) {
        var session = ChatSession.create(title);
        sessionRepository.save(session);
        log.info("会话创建成功: id={}, title={}", session.id(), session.title());
        return session;
    }

    /**
     * 列出所有会话。
     *
     * @return 会话列表
     */
    public List<SessionInfo> listSessions() {
        return sessionRepository.findAll().stream()
                .map(this::toSessionInfo)
                .toList();
    }

    /**
     * 根据条件查询会话列表。
     *
     * @param q        关键词搜索（名称或摘要）
     * @param pinned   置顶状态过滤（null 表示不过滤）
     * @param archived 归档状态过滤（null 表示不过滤）
     * @param timeRange 时间范围（7d/30d，null 表示不过滤）
     * @param sortBy   排序字段（updatedAt/lastMessageAt）
     * @param order    排序方向（asc/desc）
     * @return 会话列表
     */
    public List<SessionInfo> listSessions(String q, Boolean pinned, Boolean archived,
                                          String timeRange, String sortBy, String order) {
        return sessionRepository.findByConditions(q, pinned, archived, timeRange, sortBy, order)
                .stream()
                .map(this::toSessionInfo)
                .toList();
    }

    /**
     * 根据 ID 获取会话。
     *
     * @param id 会话 ID
     * @return 会话 Optional
     */
    public java.util.Optional<ChatSession> getSession(String id) {
        return sessionRepository.findById(id);
    }

    /**
     * 更新会话标题。
     *
     * @param id    会话 ID
     * @param title 新标题
     * @return 更新后的会话
     */
    @Transactional
    public ChatSession updateTitle(String id, String title) {
        var session = sessionRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("会话不存在: id=" + id));
        sessionRepository.updateTitle(id, title);
        log.info("会话标题更新: id={}, title={}", id, title);
        return sessionRepository.findById(id).orElse(session);
    }

    /**
     * 更新会话置顶状态。
     *
     * @param id       会话 ID
     * @param isPinned 是否置顶
     * @return 更新后的会话
     */
    @Transactional
    public ChatSession updatePinned(String id, boolean isPinned) {
        var session = sessionRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("会话不存在: id=" + id));
        sessionRepository.updatePinned(id, isPinned);
        log.info("会话置顶状态更新: id={}, isPinned={}", id, isPinned);
        return sessionRepository.findById(id).orElse(session);
    }

    /**
     * 更新会话的多个字段。
     *
     * @param id       会话 ID
     * @param title    新标题（null 表示不更新）
     * @param pinned   置顶状态（null 表示不更新）
     * @param archived 归档状态（null 表示不更新）
     * @return 更新后的会话信息
     */
    @Transactional
    public SessionInfo updateSession(String id, String title, Boolean pinned, Boolean archived) {
        var session = sessionRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("会话不存在: id=" + id));

        // 检查是否有需要更新的字段
        if (title == null && pinned == null && archived == null) {
            log.debug("没有需要更新的字段: id={}", id);
            return toSessionInfo(session);
        }

        // 更新字段
        sessionRepository.updateFields(id, title, pinned, archived);
        log.info("会话更新: id={}, title={}, pinned={}, archived={}", id, title, pinned, archived);

        // 返回更新后的会话信息
        var updatedSession = sessionRepository.findById(id)
                .orElseThrow(() -> new IllegalStateException("更新后无法找到会话: id=" + id));
        return toSessionInfo(updatedSession);
    }

    /**
     * 删除会话。
     *
     * @param id 会话 ID
     */
    @Transactional
    public void deleteSession(String id) {
        sessionRepository.deleteById(id);
        log.info("会话删除: id={}", id);
    }

    /**
     * 清空会话消息（保留会话本身）。
     *
     * @param id 会话 ID
     */
    @Transactional
    public void clearSessionMessages(String id) {
        sessionRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("会话不存在: id=" + id));
        chatMessageRepository.deleteBySessionId(id);
        sessionRepository.clearMessages(id);
        log.info("会话消息已清空: id={}", id);
    }

    /**
     * 增加会话消息计数。
     *
     * @param id 会话 ID
     */
    @Transactional
    public void incrementMessageCount(String id) {
        sessionRepository.incrementMessageCount(id);
    }

    /**
     * 获取会话的历史消息。
     *
     * @param id 会话 ID
     * @return 消息列表
     */
    public List<MessageInfo> getSessionMessages(String id) {
        // 验证会话存在
        sessionRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("会话不存在: id=" + id));

        // 对话历史与记忆系统物理解耦：仅从 chat_messages 读取历史
        return chatMessageRepository.findMessageInfosBySessionId(id);
    }

    /**
     * 批量更新会话。
     *
     * @param action    操作类型（"pin", "unpin", "archive", "unarchive", "delete"）
     * @param sessionIds 会话 ID 列表
     * @return 更新的记录数
     */
    @Transactional
    public int batchUpdateSessions(String action, List<String> sessionIds) {
        if (sessionIds == null || sessionIds.isEmpty()) {
            log.warn("批量更新会话: 会话 ID 列表为空");
            return 0;
        }

        return switch (action) {
            case "pin" -> {
                int count = sessionRepository.batchUpdateFields(sessionIds, null, true, null);
                log.info("批量置顶会话: count={}, ids={}", count, sessionIds);
                yield count;
            }
            case "unpin" -> {
                int count = sessionRepository.batchUpdateFields(sessionIds, null, false, null);
                log.info("批量取消置顶会话: count={}, ids={}", count, sessionIds);
                yield count;
            }
            case "archive" -> {
                int count = sessionRepository.batchUpdateFields(sessionIds, null, null, true);
                log.info("批量归档会话: count={}, ids={}", count, sessionIds);
                yield count;
            }
            case "unarchive" -> {
                int count = sessionRepository.batchUpdateFields(sessionIds, null, null, false);
                log.info("批量取消归档会话: count={}, ids={}", count, sessionIds);
                yield count;
            }
            case "delete" -> {
                int count = sessionRepository.batchDelete(sessionIds);
                log.info("批量删除会话: count={}, ids={}", count, sessionIds);
                yield count;
            }
            default -> {
                log.warn("未知的批量操作类型: action={}", action);
                throw new IllegalArgumentException("不支持的操作类型: " + action);
            }
        };
    }

    /**
     * 获取会话详情。
     *
     * @param id 会话 ID
     * @return 会话详情
     */
    public SessionDetailInfo getSessionDetail(String id) {
        // 查询会话基础信息
        ChatSession session = sessionRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("会话不存在: id=" + id));

        // 查询关联的知识库
        List<String> knowledgeBaseIds = sessionKnowledgeBaseRepository.findKnowledgeBaseIdsBySessionId(id);

        // 统计消息数和 Token 数
        int messageCount = session.messageCount();
        long totalTokens = 0;

        // 获取最后一条消息预览
        String lastMessagePreview = session.summary();
        // 对话历史解耦后：lastMessagePreview 由 chat_sessions.summary 维护；Token 统计暂不计算（后续可扩展字段）

        return new SessionDetailInfo(
                session.id(),
                session.title(),
                session.createdAt(),
                session.updatedAt(),
                session.isPinned(),
                session.archived(),
                knowledgeBaseIds,
                messageCount,
                totalTokens,
                lastMessagePreview
        );
    }

    /**
     * 分叉会话：从指定消息创建新会话，复制上下文。
     *
     * @param originalSessionId 原会话 ID
     * @param fromMessageId    起始消息 ID（从此消息开始复制历史，包含此消息）
     * @param newTitle         新会话标题（可选，默认使用原会话标题 + " (分叉)"）
     * @return 新创建的会话信息
     */
    @Transactional
    public SessionInfo forkSession(String originalSessionId, String fromMessageId, String newTitle) {
        // 1. 查找原会话
        ChatSession originalSession = sessionRepository.findById(originalSessionId)
                .orElseThrow(() -> new IllegalArgumentException("原会话不存在: id=" + originalSessionId));

        // 2. 获取原会话的所有历史消息（按时间排序）
        var allMessages = chatMessageRepository.findRowsBySessionId(originalSessionId);
        
        // 3. 找到指定消息的位置
        int messageIndex = -1;
        for (int i = 0; i < allMessages.size(); i++) {
            if (allMessages.get(i).id().equals(fromMessageId)) {
                messageIndex = i;
                break;
            }
        }
        
        if (messageIndex == -1) {
            throw new IllegalArgumentException("消息不存在: messageId=" + fromMessageId);
        }

        // 4. 复制该消息及其之前的所有消息（包含该消息）
        var messagesToCopy = allMessages.subList(0, messageIndex + 1);
        
        if (messagesToCopy.isEmpty()) {
            throw new IllegalArgumentException("没有可复制的消息");
        }

        // 5. 创建新会话
        String title = newTitle != null && !newTitle.isBlank() 
                ? newTitle 
                : originalSession.title() + " (分叉)";
        ChatSession newSession = ChatSession.create(title);
        sessionRepository.save(newSession);

        // 6. 写入 chat_messages（使用新的消息 ID，保留原始 createdAt 以保持顺序）
        for (var originalMsg : messagesToCopy) {
            chatMessageRepository.insert(
                    newSession.id(),
                    originalMsg.role(),
                    originalMsg.content(),
                    originalMsg.reasoningSummary(),
                    originalMsg.traceId(),
                    originalMsg.createdAt()
            );
        }

        // 7. 更新新会话统计（message_count / last_message_at / summary 预览）
        for (var originalMsg : messagesToCopy) {
            sessionRepository.appendMessageMeta(newSession.id(), originalMsg.createdAt(), preview(originalMsg.content()));
        }

        // 8. 复制会话-知识库关联
        List<String> knowledgeBaseIds = sessionKnowledgeBaseRepository
                .findKnowledgeBaseIdsBySessionId(originalSessionId);
        for (String kbId : knowledgeBaseIds) {
            sessionKnowledgeBaseRepository.addAssociation(newSession.id(), kbId);
        }

        log.info("会话分叉完成: originalSessionId={}, newSessionId={}, messageCount={}", 
                originalSessionId, newSession.id(), messagesToCopy.size());
        
        return toSessionInfo(newSession);
    }

    private String preview(String content) {
        if (content == null) {
            return null;
        }
        String t = content.strip();
        if (t.length() <= 100) {
            return t;
        }
        return t.substring(0, 100) + "...";
    }

    /**
     * 更新会话配置。
     *
     * <p>支持更新模型ID、温度参数、最大Tokens和关联的知识库列表。
     *
     * @param id      会话 ID
     * @param request 配置更新请求
     */
    @Transactional
    public void updateSessionConfig(String id, SessionConfigRequest request) {
        // 验证会话存在
        sessionRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("会话不存在: id=" + id));

        // 构建配置 Map（只包含非空字段）
        java.util.Map<String, Object> config = new java.util.HashMap<>();
        if (request.modelId() != null && !request.modelId().isBlank()) {
            config.put("modelId", request.modelId());
        }
        if (request.temperature() != null) {
            config.put("temperature", request.temperature());
        }
        if (request.maxTokens() != null) {
            config.put("maxTokens", request.maxTokens());
        }

        // 更新配置
        if (!config.isEmpty()) {
            sessionRepository.updateConfig(id, config);
            log.info("会话配置已更新: sessionId={}, config={}", id, config);
        }

        // 更新关联的知识库（如果提供了）
        if (request.knowledgeBaseIds() != null) {
            sessionKnowledgeBaseRepository.setAssociations(id, request.knowledgeBaseIds());
            log.info("会话知识库关联已更新: sessionId={}, knowledgeBaseIds={}", id, request.knowledgeBaseIds());
        }
    }

    /**
     * 将 ChatSession 转换为 SessionInfo。
     */
    private SessionInfo toSessionInfo(ChatSession session) {
        return new SessionInfo(
                session.id(),
                session.title(),
                session.createdAt(),
                session.updatedAt(),
                session.isPinned(),
                session.archived(),
                session.summary(), // 使用 summary 作为 lastMessagePreview
                session.lastMessageAt()
        );
    }
}
