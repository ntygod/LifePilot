package com.lifepilot.agent.intelligence;

import com.lifepilot.agent.intelligence.model.DecisionSignal;
import com.lifepilot.agent.intelligence.model.ToolHealth;
import com.lifepilot.agent.intelligence.config.IntelligenceProperties;
import com.lifepilot.memory.store.procedural.IntentMatcher;
import com.lifepilot.memory.store.procedural.ProcedureTemplate;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AdaptiveDecisionEngine 单元测试。
 *
 * @author zsg
 * @since 2026-06-20
 */
class AdaptiveDecisionEngine_单元测试 {

    @Test
    void 经验匹配分数低于配置阈值时不注入经验提示() {
        var intentMatcher = mock(IntentMatcher.class);
        when(intentMatcher.match("整理项目计划"))
                .thenReturn(Optional.of(new IntentMatcher.TemplateMatch(template(), 0.75f)));
        var engine = newEngine(intentMatcher, 0.8f);

        var signal = engine.buildDecisionSignal("整理项目计划", Set.of());

        assertThat(signal.experienceHint()).isNull();
    }

    @Test
    void 经验匹配分数达到配置阈值时注入经验提示() {
        var intentMatcher = mock(IntentMatcher.class);
        when(intentMatcher.match("整理项目计划"))
                .thenReturn(Optional.of(new IntentMatcher.TemplateMatch(template(), 0.75f)));
        var engine = newEngine(intentMatcher, 0.7f);

        var signal = engine.buildDecisionSignal("整理项目计划", Set.of());

        assertThat(signal.experienceHint()).isNotNull();
        assertThat(signal.experienceHint().templateName()).isEqualTo("项目计划模板");
        assertThat(signal.experienceHint().confidence()).isEqualTo(0.75f);
    }

    @Test
    void 构造器应拒绝非法经验阈值() {
        var assessor = new CapabilityAssessor(10);
        var perceptor = new EnvironmentPerceptor(assessor, 60);

        assertThatThrownBy(() -> new AdaptiveDecisionEngine(
                assessor, perceptor, null, 1.1f, Duration.ofMillis(80),
                IntelligenceProperties.ExperienceMatchTrigger.TASK_LIKE, 0, 4))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("经验匹配最低置信度");
    }

    @Test
    void 构造器应拒绝非法最少有效字符数() {
        var assessor = new CapabilityAssessor(10);
        var perceptor = new EnvironmentPerceptor(assessor, 60);

        assertThatThrownBy(() -> new AdaptiveDecisionEngine(
                assessor, perceptor, mock(IntentMatcher.class), 0.8f, Duration.ofMillis(80),
                IntelligenceProperties.ExperienceMatchTrigger.TASK_LIKE, -1, 4))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("经验匹配最少有效字符数");
    }

    @Test
    void 构造器应拒绝非法后台经验匹配任务上限() {
        var assessor = new CapabilityAssessor(10);
        var perceptor = new EnvironmentPerceptor(assessor, 60);

        assertThatThrownBy(() -> new AdaptiveDecisionEngine(
                assessor, perceptor, mock(IntentMatcher.class), 0.8f, Duration.ofMillis(80),
                IntelligenceProperties.ExperienceMatchTrigger.TASK_LIKE, 0, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("经验匹配最大后台任务数");
    }

    @Test
    void 构造器应拒绝缺少意图匹配器() {
        var assessor = new CapabilityAssessor(10);
        var perceptor = new EnvironmentPerceptor(assessor, 60);

        assertThatThrownBy(() -> new AdaptiveDecisionEngine(
                assessor, perceptor, null, 0.8f, Duration.ofMillis(80),
                IntelligenceProperties.ExperienceMatchTrigger.TASK_LIKE, 0, 4))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("意图匹配器不能为空");
    }

    @Test
    void 短输入直接跳过经验匹配不启动后台任务() {
        var intentMatcher = mock(IntentMatcher.class);
        var engine = newEngine(intentMatcher, 0.7f, Duration.ofMillis(80), 6);

        var signal = engine.buildDecisionSignal("继续", Set.of());

        assertThat(signal.experienceHint()).isNull();
        verify(intentMatcher, never()).match("继续");
    }

    @Test
    void 非任务型闲聊输入直接跳过经验匹配() {
        var intentMatcher = mock(IntentMatcher.class);
        var engine = newEngine(intentMatcher, 0.7f, Duration.ofMillis(80), 0);

        var signal = engine.buildDecisionSignal("早上好，今天状态怎么样", Set.of());

        assertThat(signal.experienceHint()).isNull();
        verify(intentMatcher, never()).match(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void 礼貌请问不应被误判为任务型输入() {
        var intentMatcher = mock(IntentMatcher.class);
        var engine = newEngine(intentMatcher, 0.7f, Duration.ofMillis(80), 0);

        var signal = engine.buildDecisionSignal("请问你是谁", Set.of());

        assertThat(signal.experienceHint()).isNull();
        verify(intentMatcher, never()).match(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void 关闭经验匹配策略时任务型输入也不启动后台任务() {
        var intentMatcher = mock(IntentMatcher.class);
        var engine = newEngine(
                intentMatcher,
                0.7f,
                Duration.ofMillis(80),
                IntelligenceProperties.ExperienceMatchTrigger.DISABLED,
                0,
                4);

        var signal = engine.buildDecisionSignal("帮我整理项目计划", Set.of());

        assertThat(signal.experienceHint()).isNull();
        verify(intentMatcher, never()).match(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void 恢复控制文本直接跳过经验匹配() {
        var intentMatcher = mock(IntentMatcher.class);
        var engine = newEngine(intentMatcher, 0.7f, Duration.ofMillis(80), 0);

        var signal = engine.buildDecisionSignal("""
                <resume_user_input>
                继续执行
                </resume_user_input>
                """, Set.of());

        assertThat(signal.experienceHint()).isNull();
        verify(intentMatcher, never()).match(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void 纯文本恢复指令也应跳过经验匹配() {
        var intentMatcher = mock(IntentMatcher.class);
        var engine = newEngine(intentMatcher, 0.7f, Duration.ofMillis(80), 0);

        var signal = engine.buildDecisionSignal("""
                从上一轮断点继续。
                恢复动作：修正后继续
                目标：Shell 执行（命令执行/COMMAND）
                恢复计划：查看命令输出并修正报错原因、从失败命令后继续执行验证
                请从这个失败点继续，不要重复已经完成的步骤。
                """, Set.of());

        assertThat(signal.experienceHint()).isNull();
        verify(intentMatcher, never()).match(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void 计划续接恢复指令也应跳过经验匹配() {
        var intentMatcher = mock(IntentMatcher.class);
        var engine = newEngine(intentMatcher, 0.7f, Duration.ofMillis(80), 0);

        var signal = engine.buildDecisionSignal("""
                按上一轮恢复计划继续。
                恢复动作：继续处理
                恢复提示：已有结果会保留，继续处理剩余部分。
                恢复计划：复用已有结果、继续处理剩余任务
                请按恢复计划继续，保留已经完成的内容。
                """, Set.of());

        assertThat(signal.experienceHint()).isNull();
        verify(intentMatcher, never()).match(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void 计划重启恢复指令也应跳过经验匹配() {
        var intentMatcher = mock(IntentMatcher.class);
        var engine = newEngine(intentMatcher, 0.7f, Duration.ofMillis(80), 0);

        var signal = engine.buildDecisionSignal("""
                重新开始上一轮任务。
                恢复动作：重新开始
                恢复计划：复用已有结果、重新处理剩余任务
                请重新开始这一轮，保留可复用信息并按恢复计划推进。
                """, Set.of());

        assertThat(signal.experienceHint()).isNull();
        verify(intentMatcher, never()).match(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void 用户要求直接回答时不启动也不复用经验匹配() {
        var intentMatcher = mock(IntentMatcher.class);
        when(intentMatcher.match("整理项目计划"))
                .thenReturn(Optional.of(new IntentMatcher.TemplateMatch(template(), 0.9f)));
        var engine = newEngine(intentMatcher, 0.7f, Duration.ofMillis(80), 0);

        assertThat(engine.buildDecisionSignal("整理项目计划", Set.of()).experienceHint())
                .isNotNull();

        var signal = engine.buildDecisionSignal("不要参考历史，直接回答：整理项目计划", Set.of());

        assertThat(signal.experienceHint()).isNull();
        verify(intentMatcher, never()).match("不要参考历史，直接回答：整理项目计划");
    }

    @Test
    void 用户明确要求直接回答时跳过经验匹配() {
        var intentMatcher = mock(IntentMatcher.class);
        var engine = newEngine(intentMatcher, 0.7f, Duration.ofMillis(80), 0);

        var signal = engine.buildDecisionSignal("请直接回答：整理项目计划", Set.of());

        assertThat(signal.experienceHint()).isNull();
        verify(intentMatcher, never()).match(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void 用户口语化要求别分析意图时跳过经验匹配() {
        var intentMatcher = mock(IntentMatcher.class);
        var engine = newEngine(intentMatcher, 0.7f, Duration.ofMillis(80), 0);

        var signal = engine.buildDecisionSignal("别分析意图，帮我整理项目计划", Set.of());

        assertThat(signal.experienceHint()).isNull();
        verify(intentMatcher, never()).match(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void 用户要求不做能力预判时跳过经验匹配() {
        var intentMatcher = mock(IntentMatcher.class);
        var engine = newEngine(intentMatcher, 0.7f, Duration.ofMillis(80), 0);

        var signal = engine.buildDecisionSignal("不做能力预判：整理项目计划", Set.of());

        assertThat(signal.experienceHint()).isNull();
        verify(intentMatcher, never()).match(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void 用户要求不用工具时也跳过经验匹配后台增强() {
        var intentMatcher = mock(IntentMatcher.class);
        var engine = newEngine(intentMatcher, 0.7f, Duration.ofMillis(80), 0);

        var signal = engine.buildDecisionSignal("不用工具，帮我整理项目计划", Set.of());

        assertThat(signal.experienceHint()).isNull();
        verify(intentMatcher, never()).match(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void 用户要求不要参考记忆时也跳过经验匹配后台增强() {
        var intentMatcher = mock(IntentMatcher.class);
        var engine = newEngine(intentMatcher, 0.7f, Duration.ofMillis(80), 0);

        var signal = engine.buildDecisionSignal("不要参考记忆，只基于本轮：整理项目计划", Set.of());

        assertThat(signal.experienceHint()).isNull();
        verify(intentMatcher, never()).match(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void 用户要求只根据给定资料时不启动也不复用经验匹配() {
        var intentMatcher = mock(IntentMatcher.class);
        when(intentMatcher.match("整理项目计划"))
                .thenReturn(Optional.of(new IntentMatcher.TemplateMatch(template(), 0.9f)));
        var engine = newEngine(intentMatcher, 0.7f, Duration.ofMillis(80), 0);

        assertThat(engine.buildDecisionSignal("整理项目计划", Set.of()).experienceHint())
                .isNotNull();

        var signal = engine.buildDecisionSignal("只根据我给的资料整理项目计划", Set.of());

        assertThat(signal.experienceHint()).isNull();
        verify(intentMatcher, never()).match("只根据我给的资料整理项目计划");
    }

    @Test
    void 用户要求只看上传附件时跳过经验匹配() {
        var intentMatcher = mock(IntentMatcher.class);
        var engine = newEngine(intentMatcher, 0.7f, Duration.ofMillis(80), 0);

        var signal = engine.buildDecisionSignal("只看上传附件，帮我总结项目计划", Set.of());

        assertThat(signal.experienceHint()).isNull();
        verify(intentMatcher, never()).match(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void 结构化关闭经验注入时不启动后台经验匹配() {
        var intentMatcher = mock(IntentMatcher.class);
        when(intentMatcher.match("整理项目计划"))
                .thenReturn(Optional.of(new IntentMatcher.TemplateMatch(template(), 0.9f)));
        var engine = newEngine(intentMatcher, 0.7f, Duration.ZERO, 0);

        var signal = engine.buildDecisionSignal("整理项目计划", Set.of(), false);

        assertThat(signal.experienceHint()).isNull();
        verify(intentMatcher, never()).match(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void 正文提到直接回答偏好时不误判为跳过经验匹配() {
        var intentMatcher = mock(IntentMatcher.class);
        when(intentMatcher.match("请总结这段话：用户喜欢直接回答"))
                .thenReturn(Optional.of(new IntentMatcher.TemplateMatch(template(), 0.9f)));
        var engine = newEngine(intentMatcher, 0.7f, Duration.ofMillis(80), 0);

        var signal = engine.buildDecisionSignal("请总结这段话：用户喜欢直接回答", Set.of());

        assertThat(signal.experienceHint()).isNotNull();
        assertThat(signal.experienceHint().templateName()).isEqualTo("项目计划模板");
        verify(intentMatcher).match("请总结这段话：用户喜欢直接回答");
    }

    @Test
    void 正文提到别分析意图偏好时不误判为跳过经验匹配() {
        var intentMatcher = mock(IntentMatcher.class);
        when(intentMatcher.match("请总结这段话：用户说别分析意图"))
                .thenReturn(Optional.of(new IntentMatcher.TemplateMatch(template(), 0.9f)));
        var engine = newEngine(intentMatcher, 0.7f, Duration.ofMillis(80), 0);

        var signal = engine.buildDecisionSignal("请总结这段话：用户说别分析意图", Set.of());

        assertThat(signal.experienceHint()).isNotNull();
        assertThat(signal.experienceHint().templateName()).isEqualTo("项目计划模板");
        verify(intentMatcher).match("请总结这段话：用户说别分析意图");
    }

    @Test
    void 正文提到不用记忆偏好时不误判为跳过经验匹配() {
        var intentMatcher = mock(IntentMatcher.class);
        when(intentMatcher.match("请总结这段话：用户说以后不要参考记忆"))
                .thenReturn(Optional.of(new IntentMatcher.TemplateMatch(template(), 0.9f)));
        var engine = newEngine(intentMatcher, 0.7f, Duration.ofMillis(80), 0);

        var signal = engine.buildDecisionSignal("请总结这段话：用户说以后不要参考记忆", Set.of());

        assertThat(signal.experienceHint()).isNotNull();
        assertThat(signal.experienceHint().templateName()).isEqualTo("项目计划模板");
        verify(intentMatcher).match("请总结这段话：用户说以后不要参考记忆");
    }

    @Test
    void 正文提到只根据给定资料偏好时不误判为跳过经验匹配() {
        var intentMatcher = mock(IntentMatcher.class);
        when(intentMatcher.match("请总结这段话：用户说以后只根据我给的资料回答"))
                .thenReturn(Optional.of(new IntentMatcher.TemplateMatch(template(), 0.9f)));
        var engine = newEngine(intentMatcher, 0.7f, Duration.ofMillis(80), 0);

        var signal = engine.buildDecisionSignal("请总结这段话：用户说以后只根据我给的资料回答", Set.of());

        assertThat(signal.experienceHint()).isNotNull();
        assertThat(signal.experienceHint().templateName()).isEqualTo("项目计划模板");
        verify(intentMatcher).match("请总结这段话：用户说以后只根据我给的资料回答");
    }

    @Test
    void 超长输入只用头尾意图探针进行经验匹配() {
        var intentMatcher = mock(IntentMatcher.class);
        when(intentMatcher.match(org.mockito.ArgumentMatchers.argThat(query ->
                query != null
                        && query.codePointCount(0, query.length()) <= 60
                        && query.contains("...")
                        && query.contains("请帮我整理项目计划"))))
                .thenReturn(Optional.of(new IntentMatcher.TemplateMatch(template(), 0.9f)));
        var engine = newEngine(intentMatcher, 0.7f, Duration.ofMillis(80),
                IntelligenceProperties.ExperienceMatchTrigger.TASK_LIKE, 0, 60, 4);
        String longGoal = "资料".repeat(120) + "\n请帮我整理项目计划";

        var signal = engine.buildDecisionSignal(longGoal, Set.of());

        assertThat(signal.experienceHint()).isNotNull();
        assertThat(signal.experienceHint().templateName()).isEqualTo("项目计划模板");
        verify(intentMatcher).match(org.mockito.ArgumentMatchers.argThat(query ->
                query != null
                        && query.codePointCount(0, query.length()) <= 60
                        && query.contains("请帮我整理项目计划")));
    }

    @Test
    void 超长无换行输入应快速截取头尾探针进行经验匹配() {
        var intentMatcher = mock(IntentMatcher.class);
        when(intentMatcher.match(org.mockito.ArgumentMatchers.argThat(query ->
                query != null
                        && query.length() <= 80
                        && query.contains("请帮我整理项目计划")
                        && query.contains("尾部执行清单"))))
                .thenReturn(Optional.of(new IntentMatcher.TemplateMatch(template(), 0.9f)));
        var engine = newEngine(intentMatcher, 0.7f, Duration.ofMillis(80),
                IntelligenceProperties.ExperienceMatchTrigger.TASK_LIKE, 0, 80, 4);
        String body = "背景资料".repeat(50_000);

        var signal = assertTimeout(Duration.ofSeconds(1), () -> engine.buildDecisionSignal(
                "请帮我整理项目计划：" + body + "尾部执行清单", Set.of()));

        assertThat(signal.experienceHint()).isNotNull();
        assertThat(signal.experienceHint().templateName()).isEqualTo("项目计划模板");
        verify(intentMatcher).match(org.mockito.ArgumentMatchers.argThat(query ->
                query != null
                        && query.length() <= 80
                        && query.contains("尾部执行清单")));
    }

    @Test
    void 超长无换行跳过意图识别指令应快速跳过经验匹配() {
        var intentMatcher = mock(IntentMatcher.class);
        var engine = newEngine(intentMatcher, 0.7f, Duration.ofMillis(80), 0);
        String body = "背景资料".repeat(50_000);

        var signal = assertTimeout(Duration.ofSeconds(1), () -> engine.buildDecisionSignal(
                "别分析意图" + body + "整理项目计划", Set.of()));

        assertThat(signal.experienceHint()).isNull();
        verify(intentMatcher, never()).match(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void 经验匹配超时应跳过本轮并在后台完成后复用结果() throws Exception {
        var intentMatcher = mock(IntentMatcher.class);
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(intentMatcher.match("整理项目计划")).thenAnswer(invocation -> {
            started.countDown();
            assertThat(release.await(1, TimeUnit.SECONDS)).isTrue();
            return Optional.of(new IntentMatcher.TemplateMatch(template(), 0.9f));
        });
        var engine = newEngine(intentMatcher, 0.7f, Duration.ofMillis(20));

        long startedAt = System.nanoTime();
        var firstSignal = engine.buildDecisionSignal("整理项目计划", Set.of());
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

        assertThat(started.await(100, TimeUnit.MILLISECONDS)).isTrue();
        assertThat(elapsedMs).isLessThan(200);
        assertThat(firstSignal.experienceHint()).isNull();

        release.countDown();

        DecisionSignal.ExperienceHint hint = null;
        for (int i = 0; i < 20; i++) {
            hint = engine.buildDecisionSignal("整理项目计划", Set.of()).experienceHint();
            if (hint != null) {
                break;
            }
            Thread.sleep(20);
        }

        assertThat(hint).isNotNull();
        assertThat(hint.templateName()).isEqualTo("项目计划模板");
    }

    @Test
    void 后台经验匹配忙时跳过新输入不启动更多任务() throws Exception {
        var intentMatcher = mock(IntentMatcher.class);
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(intentMatcher.match("整理项目计划一")).thenAnswer(invocation -> {
            started.countDown();
            assertThat(release.await(1, TimeUnit.SECONDS)).isTrue();
            return Optional.of(new IntentMatcher.TemplateMatch(template(), 0.9f));
        });
        var engine = newEngine(intentMatcher, 0.7f, Duration.ZERO, 0, 1);

        var firstSignal = engine.buildDecisionSignal("整理项目计划一", Set.of());
        assertThat(firstSignal.experienceHint()).isNull();
        assertThat(started.await(100, TimeUnit.MILLISECONDS)).isTrue();

        var secondSignal = engine.buildDecisionSignal("整理项目计划二", Set.of());

        assertThat(secondSignal.experienceHint()).isNull();
        verify(intentMatcher, never()).match("整理项目计划二");
        release.countDown();
    }

    @Test
    void 经验匹配零等待时主链路不等待但后台结果可复用() throws Exception {
        var intentMatcher = mock(IntentMatcher.class);
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(intentMatcher.match("整理项目计划")).thenAnswer(invocation -> {
            started.countDown();
            assertThat(release.await(1, TimeUnit.SECONDS)).isTrue();
            return Optional.of(new IntentMatcher.TemplateMatch(template(), 0.9f));
        });
        var engine = newEngine(intentMatcher, 0.7f, Duration.ZERO);

        long startedAt = System.nanoTime();
        var firstSignal = engine.buildDecisionSignal("整理项目计划", Set.of());
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

        assertThat(started.await(100, TimeUnit.MILLISECONDS)).isTrue();
        assertThat(elapsedMs).isLessThan(100);
        assertThat(firstSignal.experienceHint()).isNull();

        release.countDown();

        DecisionSignal.ExperienceHint hint = null;
        for (int i = 0; i < 20; i++) {
            hint = engine.buildDecisionSignal("整理项目计划", Set.of()).experienceHint();
            if (hint != null) {
                break;
            }
            Thread.sleep(20);
        }

        assertThat(hint).isNotNull();
        assertThat(hint.templateName()).isEqualTo("项目计划模板");
    }

    @Test
    void 后台经验命中后可复用到后续相关输入且不重新匹配() throws Exception {
        var intentMatcher = mock(IntentMatcher.class);
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(intentMatcher.match("整理项目计划")).thenAnswer(invocation -> {
            started.countDown();
            assertThat(release.await(1, TimeUnit.SECONDS)).isTrue();
            return Optional.of(new IntentMatcher.TemplateMatch(template(), 0.9f));
        });
        var engine = newEngine(intentMatcher, 0.7f, Duration.ZERO);

        assertThat(engine.buildDecisionSignal("整理项目计划", Set.of()).experienceHint()).isNull();
        assertThat(started.await(100, TimeUnit.MILLISECONDS)).isTrue();
        release.countDown();

        DecisionSignal.ExperienceHint exactHint = null;
        for (int i = 0; i < 20; i++) {
            exactHint = engine.buildDecisionSignal("整理项目计划", Set.of()).experienceHint();
            if (exactHint != null) {
                break;
            }
            Thread.sleep(20);
        }
        assertThat(exactHint).isNotNull();

        var followupSignal = engine.buildDecisionSignal("继续整理项目计划下一步", Set.of());

        assertThat(followupSignal.experienceHint()).isNotNull();
        assertThat(followupSignal.experienceHint().templateName()).isEqualTo("项目计划模板");
        verify(intentMatcher, never()).match("继续整理项目计划下一步");
    }

    @Test
    void 短输入不会复用最近经验提示也不启动新匹配() {
        var intentMatcher = mock(IntentMatcher.class);
        when(intentMatcher.match("整理项目计划"))
                .thenReturn(Optional.of(new IntentMatcher.TemplateMatch(template(), 0.9f)));
        var engine = newEngine(intentMatcher, 0.7f, Duration.ofMillis(80), 6);

        assertThat(engine.buildDecisionSignal("整理项目计划", Set.of()).experienceHint())
                .isNotNull();

        var shortFollowupSignal = engine.buildDecisionSignal("项目计划", Set.of());

        assertThat(shortFollowupSignal.experienceHint()).isNull();
        verify(intentMatcher, never()).match("项目计划");
    }

    @Test
    void 构造器应拒绝非法recent缓存参数() {
        var assessor = new CapabilityAssessor(10);
        var perceptor = new EnvironmentPerceptor(assessor, 60);

        assertThatThrownBy(() -> new AdaptiveDecisionEngine(
                assessor, perceptor, mock(IntentMatcher.class), 0.8f, Duration.ZERO,
                IntelligenceProperties.ExperienceMatchTrigger.TASK_LIKE, 0, 4,
                Duration.ofSeconds(-1), 8))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("recent 缓存时间");

        assertThatThrownBy(() -> new AdaptiveDecisionEngine(
                assessor, perceptor, mock(IntentMatcher.class), 0.8f, Duration.ZERO,
                IntelligenceProperties.ExperienceMatchTrigger.TASK_LIKE, 0, 4,
                Duration.ofSeconds(60), -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("recent 缓存数量");
    }

    @Test
    void 构造器应拒绝非法后台匹配超时时间() {
        var assessor = new CapabilityAssessor(10);
        var perceptor = new EnvironmentPerceptor(assessor, 60);

        assertThatThrownBy(() -> new AdaptiveDecisionEngine(
                assessor, perceptor, mock(IntentMatcher.class), 0.8f, Duration.ZERO,
                IntelligenceProperties.ExperienceMatchTrigger.TASK_LIKE, 0, 240, 4,
                Duration.ofSeconds(60), 8, Duration.ofMillis(-1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("后台任务超时时间");
    }

    @Test
    void 构造器应拒绝非法最大匹配输入长度() {
        var assessor = new CapabilityAssessor(10);
        var perceptor = new EnvironmentPerceptor(assessor, 60);

        assertThatThrownBy(() -> new AdaptiveDecisionEngine(
                assessor, perceptor, mock(IntentMatcher.class), 0.8f, Duration.ZERO,
                IntelligenceProperties.ExperienceMatchTrigger.TASK_LIKE, 0, 19, 4,
                Duration.ofSeconds(60), 8))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("经验匹配最大输入字符数");
    }

    @Test
    void 后台经验匹配超时后不重复堆积慢任务() throws Exception {
        var intentMatcher = mock(IntentMatcher.class);
        var firstStarted = new CountDownLatch(1);
        var releaseFirst = new CountDownLatch(1);
        when(intentMatcher.match("整理项目计划一")).thenAnswer(invocation -> {
            firstStarted.countDown();
            while (releaseFirst.getCount() > 0) {
                try {
                    releaseFirst.await(20, TimeUnit.MILLISECONDS);
                } catch (InterruptedException ignored) {
                    // 模拟底层意图匹配器未及时响应中断，验证后台慢任务不会重复堆积。
                }
            }
            return Optional.of(new IntentMatcher.TemplateMatch(template(), 0.9f));
        });
        when(intentMatcher.match("整理项目计划二"))
                .thenReturn(Optional.of(new IntentMatcher.TemplateMatch(template(), 0.9f)));
        var engine = newEngine(
                intentMatcher,
                0.7f,
                Duration.ofMillis(120),
                IntelligenceProperties.ExperienceMatchTrigger.TASK_LIKE,
                0,
                240,
                1,
                Duration.ofMillis(30));

        try {
            var firstSignal = engine.buildDecisionSignal("整理项目计划一", Set.of());
            assertThat(firstStarted.await(100, TimeUnit.MILLISECONDS)).isTrue();
            assertThat(firstSignal.experienceHint()).isNull();

            var secondSignal = engine.buildDecisionSignal("整理项目计划二", Set.of());

            assertThat(secondSignal.experienceHint()).isNull();
            verify(intentMatcher, never()).match("整理项目计划二");

            releaseFirst.countDown();
            DecisionSignal.ExperienceHint secondHint = null;
            for (int i = 0; i < 20; i++) {
                secondHint = engine.buildDecisionSignal("整理项目计划二", Set.of()).experienceHint();
                if (secondHint != null) {
                    break;
                }
                Thread.sleep(20);
            }

            assertThat(secondHint).isNotNull();
            assertThat(secondHint.templateName()).isEqualTo("项目计划模板");
            verify(intentMatcher).match("整理项目计划二");
        } finally {
            releaseFirst.countDown();
            engine.shutdown();
        }
    }

    @Test
    void 可用工具过多时跳过工具健康信号扫描() {
        var assessor = new CountingCapabilityAssessor();
        assessor.recordExecution("tool.bad", false, 20, "timeout");
        var intentMatcher = mock(IntentMatcher.class);
        var engine = new AdaptiveDecisionEngine(
                assessor,
                new EnvironmentPerceptor(assessor, 60),
                intentMatcher,
                0.7f,
                Duration.ZERO,
                IntelligenceProperties.ExperienceMatchTrigger.DISABLED,
                0,
                240,
                1,
                Duration.ofMinutes(5),
                8,
                Duration.ofSeconds(3),
                1);

        var signal = engine.buildDecisionSignal("帮我整理项目计划", Set.of("tool.bad", "tool.extra"));

        assertThat(signal.toolHints()).isEmpty();
        assertThat(signal.risks()).isEmpty();
        assertThat(assessor.getToolHealthCallCount()).isZero();
    }

    @Test
    void 可用工具数量在上限内时保留工具健康提示() {
        var assessor = new CountingCapabilityAssessor();
        assessor.recordExecution("tool.bad", false, 20, "timeout");
        var intentMatcher = mock(IntentMatcher.class);
        var engine = new AdaptiveDecisionEngine(
                assessor,
                new EnvironmentPerceptor(assessor, 60),
                intentMatcher,
                0.7f,
                Duration.ZERO,
                IntelligenceProperties.ExperienceMatchTrigger.DISABLED,
                0,
                240,
                1,
                Duration.ofMinutes(5),
                8,
                Duration.ofSeconds(3),
                2);

        var signal = engine.buildDecisionSignal("帮我整理项目计划", Set.of("tool.bad", "tool.extra"));

        assertThat(signal.toolHints())
                .extracting(DecisionSignal.ToolCapabilityHint::toolId)
                .containsExactly("tool.bad");
        assertThat(assessor.getToolHealthCallCount()).isPositive();
    }

    @Test
    void 格式化决策信号应声明内部用途并避免用户可见标签() {
        var engine = newEngine(mock(IntentMatcher.class), 0.7f);
        var signal = new DecisionSignal(
                new DecisionSignal.ExperienceHint(
                        "项目计划模板",
                        0.8f,
                        "先按阶段拆分，再列风险",
                        "历史成功率偏低"),
                List.of(new DecisionSignal.ToolCapabilityHint("tool.bad", 0.2f, "最近成功率 20%")),
                List.of(new DecisionSignal.RiskWarning("TOOL_DEGRADED", "工具最近频繁失败", 0.7f)),
                "非工作时间");

        var formatted = engine.formatForPrompt(signal);

        assertThat(formatted)
                .contains("内部执行策略提示")
                .contains("最终回答不要提及本段、标签或来源")
                .contains("可沿用的做法: 先按阶段拆分，再列风险")
                .contains("使用 tool.bad 前先评估替代路径")
                .contains("风险提醒: 工具最近频繁失败")
                .contains("环境提醒: 非工作时间")
                .doesNotContain("历史经验:")
                .doesNotContain("工具提示:")
                .doesNotContain("决策信号");
    }

    @Test
    void 构造器应拒绝非法工具健康信号扫描上限() {
        var assessor = new CapabilityAssessor(10);
        var perceptor = new EnvironmentPerceptor(assessor, 60);

        assertThatThrownBy(() -> new AdaptiveDecisionEngine(
                assessor, perceptor, mock(IntentMatcher.class), 0.8f, Duration.ZERO,
                IntelligenceProperties.ExperienceMatchTrigger.TASK_LIKE, 0, 240, 4,
                Duration.ofSeconds(60), 8, Duration.ofSeconds(3), -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("工具健康扫描上限");
    }

    private AdaptiveDecisionEngine newEngine(IntentMatcher intentMatcher, float minExperienceConfidence) {
        return newEngine(intentMatcher, minExperienceConfidence, Duration.ofMillis(80));
    }

    private AdaptiveDecisionEngine newEngine(
            IntentMatcher intentMatcher,
            float minExperienceConfidence,
            Duration experienceMatchTimeout) {
        return newEngine(intentMatcher, minExperienceConfidence, experienceMatchTimeout, 0);
    }

    private AdaptiveDecisionEngine newEngine(
            IntentMatcher intentMatcher,
            float minExperienceConfidence,
            Duration experienceMatchTimeout,
            int experienceMatchMinGoalChars) {
        return newEngine(intentMatcher, minExperienceConfidence, experienceMatchTimeout,
                experienceMatchMinGoalChars, 4);
    }

    private AdaptiveDecisionEngine newEngine(
            IntentMatcher intentMatcher,
            float minExperienceConfidence,
            Duration experienceMatchTimeout,
            int experienceMatchMinGoalChars,
            int experienceMatchMaxPending) {
        return newEngine(
                intentMatcher,
                minExperienceConfidence,
                experienceMatchTimeout,
                IntelligenceProperties.ExperienceMatchTrigger.TASK_LIKE,
                experienceMatchMinGoalChars,
                experienceMatchMaxPending);
    }

    private AdaptiveDecisionEngine newEngine(
            IntentMatcher intentMatcher,
            float minExperienceConfidence,
            Duration experienceMatchTimeout,
            IntelligenceProperties.ExperienceMatchTrigger experienceMatchTrigger,
            int experienceMatchMinGoalChars,
            int experienceMatchMaxPending) {
        return newEngine(
                intentMatcher,
                minExperienceConfidence,
                experienceMatchTimeout,
                experienceMatchTrigger,
                experienceMatchMinGoalChars,
                240,
                experienceMatchMaxPending);
    }

    private AdaptiveDecisionEngine newEngine(
            IntentMatcher intentMatcher,
            float minExperienceConfidence,
            Duration experienceMatchTimeout,
            IntelligenceProperties.ExperienceMatchTrigger experienceMatchTrigger,
            int experienceMatchMinGoalChars,
            int experienceMatchMaxGoalChars,
            int experienceMatchMaxPending) {
        return newEngine(
                intentMatcher,
                minExperienceConfidence,
                experienceMatchTimeout,
                experienceMatchTrigger,
                experienceMatchMinGoalChars,
                experienceMatchMaxGoalChars,
                experienceMatchMaxPending,
                Duration.ofSeconds(3));
    }

    private AdaptiveDecisionEngine newEngine(
            IntentMatcher intentMatcher,
            float minExperienceConfidence,
            Duration experienceMatchTimeout,
            IntelligenceProperties.ExperienceMatchTrigger experienceMatchTrigger,
            int experienceMatchMinGoalChars,
            int experienceMatchMaxGoalChars,
            int experienceMatchMaxPending,
            Duration experienceMatchBackgroundTimeout) {
        var assessor = new CapabilityAssessor(10);
        return new AdaptiveDecisionEngine(
                assessor,
                new EnvironmentPerceptor(assessor, 60),
                intentMatcher,
                minExperienceConfidence,
                experienceMatchTimeout,
                experienceMatchTrigger,
                experienceMatchMinGoalChars,
                experienceMatchMaxGoalChars,
                experienceMatchMaxPending,
                Duration.ofMinutes(5),
                8,
                experienceMatchBackgroundTimeout);
    }

    private ProcedureTemplate template() {
        return new ProcedureTemplate(
                "tpl-project-plan",
                "项目计划模板",
                "按阶段整理项目计划",
                "整理项目计划",
                List.of(),
                Map.of(),
                0.85f,
                3,
                null,
                List.of("trace-1"),
                Instant.now(),
                Instant.now(),
                null,
                null
        );
    }

    private static class CountingCapabilityAssessor extends CapabilityAssessor {
        private int toolHealthCallCount;

        CountingCapabilityAssessor() {
            super(10);
        }

        @Override
        public ToolHealth getToolHealth(String toolId) {
            toolHealthCallCount += 1;
            return super.getToolHealth(toolId);
        }

        int getToolHealthCallCount() {
            return toolHealthCallCount;
        }
    }
}
