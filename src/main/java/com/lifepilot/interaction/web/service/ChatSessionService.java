package com.lifepilot.interaction.web.service;

import com.lifepilot.interaction.web.model.ChatSession;
import com.lifepilot.interaction.web.model.MessageInfo;
import com.lifepilot.interaction.web.model.SessionInfo;
import com.lifepilot.interaction.web.repository.ChatSessionRepository;
import com.lifepilot.memory.episodic.EpisodicMemory;
import com.lifepilot.memory.episodic.MessageRecord;
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
    private final EpisodicMemory episodicMemory;

    public ChatSessionService(ChatSessionRepository sessionRepository, EpisodicMemory episodicMemory) {
        this.sessionRepository = sessionRepository;
        this.episodicMemory = episodicMemory;
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

        // 从情景记忆中查询消息
        List<MessageRecord> records = episodicMemory.getMessagesBySessionId(id);
        
        // 转换为 MessageInfo（a2ui 暂时为 null，因为未存储在 messages 表中）
        return records.stream()
                .map(record -> new MessageInfo(
                        record.id(),
                        record.role(),
                        record.effectiveContent(), // 使用有效内容（优先压缩内容）
                        null, // a2ui 组件树暂未持久化，后续可扩展
                        record.createdAt()
                ))
                .toList();
    }

    /**
     * 将 ChatSession 转换为 SessionInfo。
     */
    private SessionInfo toSessionInfo(ChatSession session) {
        return new SessionInfo(
                session.id(),
                session.title(),
                session.createdAt(),
                session.updatedAt()
        );
    }
}
