package com.lifepilot.memory.support;

import com.lifepilot.generation.router.GenerationRouter;
import java.time.Instant;
import java.time.ZoneId;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

/**
 * 场景 E2E 测试专用 Spring 配置 — 仅替换时间/调度/LLM 三类外部依赖，其余（SemanticMemory、
 * 事件总线、Phase 0 7 监听器、Flyway 迁移出来的 SQLite DB 等）全部保持真实装配。
 *
 * <p>替身一览：
 * <ul>
 *   <li>{@link LlmFixture} — 通过 classpath 下 {@code llm-fixtures/*.json} 预录 LLM 响应</li>
 *   <li>{@link GenerationRouter} → {@link FixtureBackedGenerationRouter} — 覆盖
 *       {@code call(...)} 与 {@code getChatModelWithInfo(...)}，后者内部返回
 *       {@link FixtureBackedChatModel}，使 {@code ReactAgentLoop.run} 的
 *       {@code ChatModel.call(Prompt)} 路径能通过 fixture 驱动</li>
 *   <li>{@link MutableClock} — 固定初始时刻 2026-04-23T10:00:00Z (Asia/Shanghai)，
 *       测试通过 {@code clock.advance(Duration)} 推进</li>
 *   <li>{@code TaskScheduler} → {@link ManualTaskScheduler} — 不启真实调度线程，
 *       所有任务入内存队列，测试显式调用 {@code triggerDueAt(now)} 触发</li>
 * </ul>
 *
 * <p>所有替身均带 {@link Primary} 注解覆盖生产 Bean；由于生产代码未在 Spring 容器中注册
 * {@code @Bean ChatModel}，本配置无需再声明 ChatModel Bean（ReactAgentLoop 通过
 * {@link GenerationRouter#getChatModelWithInfo} 取 ChatModel 而非容器注入）。</p>
 *
 * <p>Profile 限定：本类带 {@link Profile @Profile("scenario-test")}，仅在场景测试基类声明
 * {@code @ActiveProfiles("scenario-test")} 时才激活 bean 方法。该限定是防御性手段 —— 由于
 * {@code LifePilotApplication.@ComponentScan("com.lifepilot")} 会把测试 classpath 下所有
 * {@code @TestConfiguration} 都当作普通 {@code @Configuration} 扫入（Spring Boot 默认的
 * {@code TypeExcludeFilter/TestTypeExcludeFilter} 被我们显式的 {@code excludeFilters} 覆盖），
 * 若不加 {@code @Profile}，其他 {@code @SpringBootTest(classes = LifePilotApplication.class)}
 * 测试（如 {@code KnowledgeRuntimeAutoConfigurationIT}）会误吸本配置的 bean，与同样被扫入的
 * {@code SkillTestSupport.generationRouter()} 产生 {@link
 * org.springframework.beans.factory.support.BeanDefinitionOverrideException}。</p>
 *
 * <p>典型用法 — 测试类用 {@code @Import(ScenarioTestConfiguration.class)} +
 * {@code @ActiveProfiles("scenario-test")} 叠加本配置，然后 {@code @Autowired} 注入替身
 * 并在测试中推进时间、触发调度、加载 fixture。</p>
 *
 * @author zsg
 * @since 2026-04-23
 */
@TestConfiguration
@Profile("scenario-test")
public class ScenarioTestConfiguration {

    /**
     * LLM 响应 fixture — 测试类调用 {@code fixture.load("场景名")} 加载 JSON。
     */
    @Bean
    public LlmFixture llmFixture() {
        return new LlmFixture();
    }

    /**
     * 用 fixture-backed 路由器覆盖生产 {@link GenerationRouter}。
     *
     * <p>生产 GenerationRouter 依赖 ModelServiceRegistry / CircuitBreakerManager 等大量
     * 服务；在场景测试里若真启这些服务会大大拖慢启动且无意义。{@link FixtureBackedGenerationRouter}
     * 通过 {@code super(null, null, null, null)} 跳过字段初始化，仅覆盖被调用方法。</p>
     *
     * <p>bean name 故意选择非默认（非 {@code generationRouter}）— 避免与
     * {@code SkillTestSupport.generationRouter()}（也是 {@code @TestConfiguration}、
     * 也会被 {@code @ComponentScan} 扫到）重名导致 Spring 抛
     * {@link org.springframework.beans.factory.support.BeanDefinitionOverrideException}。
     * {@link Primary} 语义只看 bean 是否带 {@code @Primary}，不看名字，所以重命名不影响覆盖效果。</p>
     */
    @Bean(name = "fixtureGenerationRouter")
    @Primary
    public GenerationRouter fixtureGenerationRouter(LlmFixture fixture) {
        return new FixtureBackedGenerationRouter(fixture);
    }

    /**
     * 可推进测试时钟 — 初始时刻 2026-04-23T10:00:00Z，时区 Asia/Shanghai。
     */
    @Bean
    @Primary
    public MutableClock testClock() {
        return new MutableClock(
                Instant.parse("2026-04-23T10:00:00Z"),
                ZoneId.of("Asia/Shanghai"));
    }

    /**
     * 手动触发调度器 — 与 {@link MutableClock} 共用同一时钟源，保证
     * {@code scheduleWithFixedDelay(task, delay)} 计算的 fireAt 与测试推进的
     * 当前时间对齐。
     */
    @Bean
    @Primary
    public ManualTaskScheduler testScheduler(MutableClock clock) {
        return new ManualTaskScheduler(clock);
    }

    /**
     * ZhiweiPaths mock — 场景测试不依赖真实目录结构，提供临时目录兜底。
     */
    @Bean
    @Primary
    public com.lifepilot.config.path.ZhiweiPaths scenarioZhiweiPaths() {
        var paths = org.mockito.Mockito.mock(com.lifepilot.config.path.ZhiweiPaths.class);
        var tmpDir = java.nio.file.Path.of(System.getProperty("java.io.tmpdir"), "zhiwei-scenario-test");
        org.mockito.Mockito.lenient().when(paths.home()).thenReturn(tmpDir);
        org.mockito.Mockito.lenient().when(paths.home(org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(inv -> tmpDir.resolve(inv.getArgument(0, String.class)));
        org.mockito.Mockito.lenient().when(paths.workspace()).thenReturn(tmpDir.resolve("workspace"));
        return paths;
    }
}

