package com.lifepilot.memory.scenarios;

import com.lifepilot.LifePilotApplication;
import com.lifepilot.agent.ReactAgentLoop;
import com.lifepilot.agent.TurnResult;
import com.lifepilot.agent.learning.feedback.FeedbackProcessor;
import com.lifepilot.memory.governance.lifecycle.query.MemoryQueryApi;
import com.lifepilot.memory.store.entity.TemporalEntity;
import com.lifepilot.memory.store.support.FeedbackGateway;
import com.lifepilot.memory.store.support.LlmFixture;
import com.lifepilot.memory.store.support.ManualTaskScheduler;
import com.lifepilot.memory.store.support.MutableClock;
import com.lifepilot.memory.store.support.ScenarioTestConfiguration;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 场景 E2E 测试基类 — 子类以中文 DSL 驱动"发消息 / 推进时间 / 触发调度 / 查实体 / 提交反馈"，
 * 从而复现 Phase 4 产品验收清单里的 S1-S16 场景。
 *
 * <p>装配原则：仅 LLM 相关依赖（{@code GenerationRouter} / {@code ChatModel}）与时间/调度替身
 * 走 {@link ScenarioTestConfiguration}，其余全部使用生产真实装配 — 包括 SemanticMemory、
 * Phase 0 的 7 个监听器、事件总线、Flyway 迁移出来的文件 SQLite DB。这样一场景测试跑完，
 * 所能覆盖到的生产代码路径与实际运行时一致。</p>
 *
 * <p>Spring 上下文策略：仅启用场景测试真正需要的模块，其他（gateway / a2a / multimodal
 * / marketplace / meta / workflow / sandbox 等）显式关闭，避免 mock 污染和无关初始化成本。</p>
 *
 * <p>当前仅注入 Phase 0 已实装组件 — {@code ExpirationScanner}、{@code DerivationRegenerator}
 * 等 Phase 3 组件的 DSL 方法待 Task 26 / 29 落地后再扩展。</p>
 *
 * @author zsg
 * @since 2026-04-23
 */
@SpringBootTest(
        classes = LifePilotApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "lifepilot.memory.enabled=true",
                "lifepilot.agent.enabled=true",
                "lifepilot.agent.task.enabled=false",
                "lifepilot.agent.proactive.enabled=false",
                "lifepilot.agent.multi-agent.enabled=false",
                "lifepilot.agent.checkpoint.enabled=false",
                "lifepilot.llm.enabled=true",
                "lifepilot.tool.enabled=true",
                "lifepilot.skills.enabled=false",
                "lifepilot.knowledge.enabled=false",
                "lifepilot.gateway.enabled=false",
                "lifepilot.gateway.channels.web.enabled=false",
                "lifepilot.workflow.enabled=false",
                "lifepilot.sandbox.enabled=false",
                "lifepilot.media.enabled=false",
                "lifepilot.marketplace.enabled=false",
                "lifepilot.notification.enabled=false",
                "lifepilot.meta.enabled=false",
                "lifepilot.mcp.enabled=false",
                "lifepilot.a2a.enabled=false",
                "lifepilot.cli.enabled=false",
                "lifepilot.observability.trace.enabled=true",
                "lifepilot.observability.guardrail.enabled=false",
                "lifepilot.observability.evaluation.enabled=false"
        }
)
@ActiveProfiles("scenario-test")
@Import(ScenarioTestConfiguration.class)
public abstract class 场景测试基类 {

    /** 每个测试进程使用独立的文件 SQLite，避免并发执行时互相污染。 */
    private static final String DB_ID = UUID.randomUUID().toString().substring(0, 8);

    @DynamicPropertySource
    static void 配置场景测试数据库(DynamicPropertyRegistry registry) {
        var tmpDir = System.getProperty("java.io.tmpdir");
        var dbPath = Path.of(tmpDir, "lifepilot-scenario-" + DB_ID + ".db")
                .toString()
                .replace("\\", "/");
        var vecDbPath = Path.of(tmpDir, "lifepilot-scenario-vec-" + DB_ID + ".db")
                .toString()
                .replace("\\", "/");
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + dbPath);
        registry.add("lifepilot.memory.store.vector-db-url", () -> "jdbc:sqlite:" + vecDbPath);
    }

    @Autowired protected ReactAgentLoop agentLoop;
    @Autowired protected MutableClock clock;
    @Autowired protected LlmFixture fixture;
    @Autowired protected ManualTaskScheduler scheduler;
    @Autowired protected MemoryQueryApi queryApi;
    @Autowired protected FeedbackProcessor feedbackProcessor;

    /** 由 {@link #初始化Session()} 每测试前重置，避免跨 case session 污染。 */
    protected String testSessionId;

    /** 非 Spring bean，用 feedbackProcessor 构造；保留字段以方便 DSL 方法复用。 */
    protected FeedbackGateway feedbackGateway;

    @BeforeEach
    void 初始化Session() {
        testSessionId = "scenario-" + UUID.randomUUID();
        feedbackGateway = new FeedbackGateway(feedbackProcessor);
    }

    // ===== 对话 DSL =====

    /**
     * 模拟用户发一条消息，跑一轮 {@link ReactAgentLoop#run} 并返回结构化结果。
     *
     * <p>依赖 {@link LlmFixture} 已通过 {@code fixture.load("场景名")} 加载对应 fixture，
     * 否则 fixture 内部会抛 {@link IllegalStateException}。</p>
     */
    protected TurnResult 模拟用户说(String text) {
        return agentLoop.run(testSessionId, new UserMessage(text));
    }

    // ===== 时间与调度 DSL =====

    /** 向前推进测试时钟，调用方随后可通过 {@link #触发到期Scheduler()} 触发到期任务。 */
    protected void 时间推进(Duration duration) {
        clock.advance(duration);
    }

    /**
     * 触发 {@link ManualTaskScheduler} 里所有 {@code nextFireAt <= clock.now} 的任务。
     *
     * @return 被触发任务的描述列表（顺序与注册顺序一致），方便测试断言触发了哪些任务
     */
    protected List<String> 触发到期Scheduler() {
        return scheduler.triggerDueAt(clock.instant());
    }

    // ===== 查询 DSL =====

    /**
     * 按 ID 查实体 — 测试场景里多数查询预期命中，直接 {@code orElseThrow} 省略 Optional 解包。
     *
     * @param id 实体 ID
     * @return 实体实例；不存在时抛 {@link AssertionError}
     */
    protected TemporalEntity 查实体(String id) {
        return queryApi.findById(id)
                .orElseThrow(() -> new AssertionError("实体不存在: " + id));
    }

    // ===== 反馈 DSL =====

    /** 对助手 transcript 条目提交点踩，触发注入记忆 importanceScore 扣减。 */
    protected void 提交点踩(String assistantEntryId) {
        feedbackGateway.dislike(assistantEntryId);
    }

    /** 对助手 transcript 条目提交点赞，触发注入记忆 importanceScore 加成。 */
    protected void 提交点赞(String assistantEntryId) {
        feedbackGateway.like(assistantEntryId);
    }

    // 注：Phase 3 组件（ExpirationScanner / DerivationRegenerator）的 DSL 方法
    // 待 Task 26 / 29 组件落地后在子类或本基类扩展。
}
