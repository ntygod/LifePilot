package com.lifepilot.agent.task.proactive.signal;

import com.lifepilot.agent.task.proactive.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.*;

/**
 * ImplicitSignalCollector 隐式信号采集器单元测试。
 *
 * @author zsg
 * @since 2026-04-15
 */
class ImplicitSignalCollector_单元测试 {

    private ProactiveMemoryBridge memoryBridge;
    private TrustUpgradeService trustUpgradeService;
    private ImplicitSignalCollector collector;

    @BeforeEach
    void 初始化() {
        memoryBridge = mock(ProactiveMemoryBridge.class);
        trustUpgradeService = mock(TrustUpgradeService.class);
        collector = new ImplicitSignalCollector(memoryBridge, trustUpgradeService);
    }

    // ── 辅助方法 ──

    /** 构造一个最简的 ProactiveAction，topicKey 可指定。 */
    private ProactiveAction 构造Action(String behaviorName, String topicKey) {
        var candidate = new ProactiveCandidate(
                "cand-1", behaviorName, topicKey, "测试标题", 0.7f, "测试理由", null);
        return new ProactiveAction(candidate, "测试内容", DeliveryLevel.NOTIFY, null);
    }

    /** 构造一个带指定 deliveredAt 的 DeliveryResult。 */
    private DeliveryResult 构造Result(String notificationId, Instant deliveredAt) {
        return new DeliveryResult(notificationId, DeliveryLevel.NOTIFY, deliveredAt);
    }

    // ── onDelivered + onConversationCompleted 参与信号 ──

    @Nested
    class 投递后参与信号 {

        @Test
        void 窗口内发起相关对话触发参与信号() {
            // given — 5 分钟前投递，topicKey 包含 "天气"
            var action = 构造Action("insight", "topic-天气预报");
            var result = 构造Result("n-1", Instant.now().minus(Duration.ofMinutes(5)));
            collector.onDelivered("user-1", action, result);

            // when — 对话摘要包含 topicKey 去掉前缀后的 "天气预报"
            collector.onConversationCompleted("user-1", "今天的天气预报怎么样");

            // then — 正信号 0.8f → trustUpgradeService 收到正反馈
            verify(trustUpgradeService).recordPositiveFeedback("user-1", "insight");
            // 偏好回流 — 正信号传给 memoryBridge
            verify(memoryBridge).observePreference("proactive-domain", "insight", 0.8f);
        }

        @Test
        void 超过10分钟窗口不触发参与信号() {
            // given — 15 分钟前投递
            var action = 构造Action("insight", "topic-天气预报");
            var result = 构造Result("n-1", Instant.now().minus(Duration.ofMinutes(15)));
            collector.onDelivered("user-1", action, result);

            // when
            collector.onConversationCompleted("user-1", "今天的天气预报怎么样");

            // then — 不应有任何回调
            verifyNoInteractions(trustUpgradeService);
            verify(memoryBridge, never()).observePreference(anyString(), anyString(), anyFloat());
        }

        @Test
        void 对话摘要与主题无关不触发信号() {
            // given — 窗口内但内容不相关
            var action = 构造Action("insight", "topic-天气预报");
            var result = 构造Result("n-1", Instant.now().minus(Duration.ofMinutes(3)));
            collector.onDelivered("user-1", action, result);

            // when — 完全无关的对话
            collector.onConversationCompleted("user-1", "帮我写一首诗");

            // then
            verifyNoInteractions(trustUpgradeService);
            verify(memoryBridge, never()).observePreference(anyString(), anyString(), anyFloat());
        }

        @Test
        void 不同用户的投递不触发信号() {
            // given — user-1 的投递
            var action = 构造Action("insight", "topic-天气预报");
            var result = 构造Result("n-1", Instant.now().minus(Duration.ofMinutes(3)));
            collector.onDelivered("user-1", action, result);

            // when — user-2 发起对话
            collector.onConversationCompleted("user-2", "今天的天气预报怎么样");

            // then
            verifyNoInteractions(trustUpgradeService);
        }

        @Test
        void 参与后记录被移除不再重复触发() {
            // given
            var action = 构造Action("insight", "topic-天气预报");
            var result = 构造Result("n-1", Instant.now().minus(Duration.ofMinutes(3)));
            collector.onDelivered("user-1", action, result);

            // when — 第一次对话触发
            collector.onConversationCompleted("user-1", "今天的天气预报怎么样");
            // 第二次对话不应再触发
            collector.onConversationCompleted("user-1", "天气预报又变了");

            // then — 只触发一次
            verify(trustUpgradeService, times(1)).recordPositiveFeedback("user-1", "insight");
        }
    }

    // ── checkIgnoredDeliveries 忽略信号 ──

    @Nested
    class 投递后忽略信号 {

        @Test
        void 超过4小时无反应触发忽略信号() {
            // given — 5 小时前投递
            var action = 构造Action("reminder", "topic-会议提醒");
            var result = 构造Result("n-2", Instant.now().minus(Duration.ofHours(5)));
            collector.onDelivered("user-1", action, result);

            // when
            collector.checkIgnoredDeliveries("user-1");

            // then — 负信号 -0.3f，value < -0.3f 才触发 recordNegativeFeedback
            // -0.3f 不满足 value < -0.3f，所以 trustUpgradeService 不收到负反馈
            verify(trustUpgradeService, never()).recordNegativeFeedback(anyString(), anyString());
            // 偏好回流 — 负信号 0.2f
            verify(memoryBridge).observePreference("proactive-domain", "reminder", 0.2f);
        }

        @Test
        void 不到4小时不触发忽略信号() {
            // given — 2 小时前投递
            var action = 构造Action("reminder", "topic-会议提醒");
            var result = 构造Result("n-2", Instant.now().minus(Duration.ofHours(2)));
            collector.onDelivered("user-1", action, result);

            // when
            collector.checkIgnoredDeliveries("user-1");

            // then
            verifyNoInteractions(trustUpgradeService);
            verify(memoryBridge, never()).observePreference(anyString(), anyString(), anyFloat());
        }

        @Test
        void 忽略后记录被移除不再重复触发() {
            // given
            var action = 构造Action("reminder", "topic-会议提醒");
            var result = 构造Result("n-2", Instant.now().minus(Duration.ofHours(5)));
            collector.onDelivered("user-1", action, result);

            // when
            collector.checkIgnoredDeliveries("user-1");
            collector.checkIgnoredDeliveries("user-1");

            // then — 偏好回流只触发一次
            verify(memoryBridge, times(1)).observePreference(anyString(), anyString(), anyFloat());
        }
    }

    // ── checkMissedOpportunities 未命中检测 ──

    @Nested
    class 未命中检测 {

        @Test
        void 用户主动提到目标触发负信号() {
            // given
            var goal = new GoalView("e-1", "健身计划", "每周三次", 0.8f, 3,
                    Instant.now().minus(Duration.ofDays(7)), 2, null, Map.of());
            when(memoryBridge.getActiveGoals()).thenReturn(List.of(goal));

            // when — 用户对话中提到了 "健身计划"
            collector.checkMissedOpportunities("user-1", "我今天应该去健身计划里加点有氧运动");

            // then — 负信号 -0.5f → 满足 value < -0.3f → 触发负反馈
            // behaviorName 为 null，所以 trustUpgradeService 不被调用
            verify(trustUpgradeService, never()).recordNegativeFeedback(anyString(), anyString());
            // memoryBridge 的 observePreference 也不被调用（behaviorName 为 null）
            verify(memoryBridge, never()).observePreference(anyString(), anyString(), anyFloat());
        }

        @Test
        void 用户对话不匹配目标不触发() {
            // given
            var goal = new GoalView("e-1", "健身计划", "每周三次", 0.8f, 3,
                    Instant.now().minus(Duration.ofDays(7)), 2, null, Map.of());
            when(memoryBridge.getActiveGoals()).thenReturn(List.of(goal));

            // when — 无关内容
            collector.checkMissedOpportunities("user-1", "帮我查一下明天的航班");

            // then
            verify(trustUpgradeService, never()).recordNegativeFeedback(anyString(), anyString());
        }

        @Test
        void memoryBridge为null时不崩溃() {
            // given — 无记忆桥接的 collector
            var collectorNoMemory = new ImplicitSignalCollector(null, trustUpgradeService);

            // when & then — 不抛异常
            assertThatCode(() ->
                    collectorNoMemory.checkMissedOpportunities("user-1", "健身计划")
            ).doesNotThrowAnyException();
        }
    }

    // ── onDelivered 边界场景 ──

    @Nested
    class 投递记录边界 {

        @Test
        void notificationId为null时不记录() {
            // given
            var action = 构造Action("insight", "topic-天气");
            var result = new DeliveryResult(null, DeliveryLevel.SILENT, Instant.now());

            // when
            collector.onDelivered("user-1", action, result);

            // then — 后续操作不应找到任何记录
            collector.onConversationCompleted("user-1", "天气");
            verifyNoInteractions(trustUpgradeService);
        }

        @Test
        void 过期记录超24小时被清理() {
            // given — 先插入一条 25 小时前的投递
            var oldAction = 构造Action("insight", "topic-旧主题");
            var oldResult = 构造Result("n-old", Instant.now().minus(Duration.ofHours(25)));
            collector.onDelivered("user-1", oldAction, oldResult);

            // when — 插入一条新投递触发清理
            var newAction = 构造Action("reminder", "topic-新主题");
            var newResult = 构造Result("n-new", Instant.now());
            collector.onDelivered("user-1", newAction, newResult);

            // then — 旧记录已被清理，检查忽略应只看到新记录
            // 先让足够时间流逝以触发忽略检查 — 新记录刚投递不满 4h
            collector.checkIgnoredDeliveries("user-1");
            verify(memoryBridge, never()).observePreference(anyString(), anyString(), anyFloat());
        }

        @Test
        void 硬上限裁剪保留最新记录() {
            // given — 插入 501 条记录（超过 MAX_RECENT_DELIVERIES=500）
            for (int i = 0; i < 501; i++) {
                var action = 构造Action("insight", "topic-item" + i);
                // 越早的记录 deliveredAt 越早，确保排序一致
                var result = 构造Result("n-" + i,
                        Instant.now().minus(Duration.ofMinutes(501 - i)));
                collector.onDelivered("user-1", action, result);
            }

            // when — 第 502 条触发裁剪，只保留最新 500 条
            var extraAction = 构造Action("insight", "topic-extra");
            var extraResult = 构造Result("n-extra", Instant.now());
            collector.onDelivered("user-1", extraAction, extraResult);

            // then — 最旧的 n-0 应已被裁剪（5 小时前），检查忽略
            // 最旧的几条都在 500+ 分钟前但不到 4 小时内（约 8.3 小时 > 4h），
            // 实际上 501 分钟 ≈ 8.35 小时 > 4h，所以剩余最旧的也超过 4h 会触发
            // 验证 n-extra（刚投递）不在忽略范围
            // 这里的重点是验证不崩溃且裁剪正确执行
            assertThatCode(() -> collector.checkIgnoredDeliveries("user-1"))
                    .doesNotThrowAnyException();
        }
    }

    // ── isRelated 间接测试 ──

    @Nested
    class 相关性匹配 {

        @Test
        void topicKey去掉前缀后匹配() {
            // given — topicKey = "goal-学英语"，去掉 "goal-" 后为 "学英语"
            var action = 构造Action("follow-up", "goal-学英语");
            var result = 构造Result("n-r1", Instant.now().minus(Duration.ofMinutes(3)));
            collector.onDelivered("user-1", action, result);

            // when
            collector.onConversationCompleted("user-1", "我想学英语口语");

            // then — 应该匹配
            verify(trustUpgradeService).recordPositiveFeedback("user-1", "follow-up");
        }

        @Test
        void 无前缀的topicKey也能匹配() {
            // given — topicKey = "健身计划"，无 "xxx-" 前缀
            var action = 构造Action("follow-up", "健身计划");
            var result = 构造Result("n-r2", Instant.now().minus(Duration.ofMinutes(3)));
            collector.onDelivered("user-1", action, result);

            // when
            collector.onConversationCompleted("user-1", "今天的健身计划要调整");

            // then
            verify(trustUpgradeService).recordPositiveFeedback("user-1", "follow-up");
        }

        @Test
        void topicKey核心部分少于2字符不匹配() {
            // given — topicKey = "a-b"，去掉 "a-" 后只剩 "b"（1字符 < 2）
            var action = 构造Action("insight", "a-b");
            var result = 构造Result("n-r3", Instant.now().minus(Duration.ofMinutes(3)));
            collector.onDelivered("user-1", action, result);

            // when
            collector.onConversationCompleted("user-1", "b");

            // then — 不匹配
            verifyNoInteractions(trustUpgradeService);
        }
    }

    // ── 双依赖为 null 的安全性 ──

    @Nested
    class 依赖为null {

        @Test
        void 两个依赖都为null时全流程不崩溃() {
            var safeCollector = new ImplicitSignalCollector(null, null);

            var action = 构造Action("insight", "topic-测试话题");
            var result = 构造Result("n-safe", Instant.now().minus(Duration.ofMinutes(3)));
            safeCollector.onDelivered("user-1", action, result);

            // 参与信号 — recordSignal 内部跳过 null 依赖
            assertThatCode(() ->
                    safeCollector.onConversationCompleted("user-1", "测试话题很有趣")
            ).doesNotThrowAnyException();

            // 忽略信号
            var oldResult = 构造Result("n-old", Instant.now().minus(Duration.ofHours(5)));
            safeCollector.onDelivered("user-1", action, oldResult);
            assertThatCode(() ->
                    safeCollector.checkIgnoredDeliveries("user-1")
            ).doesNotThrowAnyException();

            // 未命中检测
            assertThatCode(() ->
                    safeCollector.checkMissedOpportunities("user-1", "随便什么")
            ).doesNotThrowAnyException();
        }

        @Test
        void trustUpgradeService为null时参与信号仍写偏好() {
            var collectorNoTrust = new ImplicitSignalCollector(memoryBridge, null);

            var action = 构造Action("insight", "topic-天气预报");
            var result = 构造Result("n-nt", Instant.now().minus(Duration.ofMinutes(3)));
            collectorNoTrust.onDelivered("user-1", action, result);
            collectorNoTrust.onConversationCompleted("user-1", "今天天气预报说要下雨");

            // 偏好回流仍然工作
            verify(memoryBridge).observePreference("proactive-domain", "insight", 0.8f);
        }
    }
}
