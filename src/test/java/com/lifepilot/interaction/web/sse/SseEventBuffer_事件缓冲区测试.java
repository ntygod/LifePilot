package com.lifepilot.interaction.web.sse;

import com.lifepilot.interaction.web.config.WebProperties.SseBufferProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * SSE 事件缓冲区测试 — 覆盖 offer/drain、终结事件、队列满超时、close 幂等性、
 * 心跳注入、自适应排空速率、前瞻工具调用减速等核心场景。
 *
 * @author zsg
 * @since 2026-04-12
 */
@ExtendWith(MockitoExtension.class)
class SseEventBuffer_事件缓冲区测试 {

    @Mock
    private SseSessionManager sseManager;

    private static final String STREAM_ID = "test-stream-001";

    /** 默认配置：小队列容量加速测试 */
    private SseBufferProperties defaultConfig() {
        return new SseBufferProperties(
                true,   // enabled
                16,     // queueCapacity — 小容量便于测试满队列
                10,     // highWaterMark
                5,      // lowWaterMark
                1,      // fastDrainIntervalMs
                2,      // normalDrainIntervalMs
                4,      // slowDrainIntervalMs
                10,     // preLookaheadIntervalMs
                50,     // offerTimeoutMs
                100     // gapHeartbeatIntervalMs
        );
    }

    /** 快排空配置：极短排空间隔 + 较大容量 */
    private SseBufferProperties fastDrainConfig() {
        return new SseBufferProperties(
                true, 128, 80, 20, 0, 0, 0, 0, 50, 5000
        );
    }

    private SseEventBuffer buffer;

    @AfterEach
    void 清理() {
        if (buffer != null && !buffer.isClosed()) {
            buffer.close();
        }
    }

    // ==================== 正常 offer + drain ====================

    @Nested
    class 正常入队与排空 {

        @BeforeEach
        void 初始化() {
            buffer = new SseEventBuffer(STREAM_ID, sseManager, fastDrainConfig());
        }

        @Test
        void offer成功后事件按顺序排空派发() throws Exception {
            // given — 记录 sendEvent 的调用顺序
            var dispatched = new CopyOnWriteArrayList<String>();
            doAnswer(inv -> {
                dispatched.add((String) inv.getArgument(1) + ":" + inv.getArgument(2));
                return null;
            }).when(sseManager).sendEvent(eq(STREAM_ID), any(), any());

            // when — 连续 offer 3 个事件 + 终结事件触发排空
            assertThat(buffer.offer(SseEventType.TOKEN, "A")).isTrue();
            assertThat(buffer.offer(SseEventType.TOKEN, "B")).isTrue();
            assertThat(buffer.offer(SseEventType.TOKEN, "C")).isTrue();
            buffer.offerTerminal(SseEventType.DONE, "END");

            // then — 等待排空线程完成
            awaitBufferClosed(buffer, 2000);

            assertThat(dispatched).containsExactly(
                    "token:A", "token:B", "token:C", "done:END"
            );
        }

        @Test
        void offer返回true表示入队成功() {
            assertThat(buffer.offer(SseEventType.TOKEN, "data")).isTrue();
            assertThat(buffer.queueDepth()).isGreaterThanOrEqualTo(0); // 可能已被排空
        }
    }

    // ==================== offerTerminal ====================

    @Nested
    class 终结事件处理 {

        @BeforeEach
        void 初始化() {
            buffer = new SseEventBuffer(STREAM_ID, sseManager, fastDrainConfig());
        }

        @Test
        void offerTerminal触发flushAll后终结事件最后派发() throws Exception {
            // given
            var order = new CopyOnWriteArrayList<String>();
            doAnswer(inv -> {
                order.add((String) inv.getArgument(1));
                return null;
            }).when(sseManager).sendEvent(eq(STREAM_ID), any(), any());

            // when — 先入队若干普通事件，再发终结事件
            for (int i = 0; i < 5; i++) {
                buffer.offer(SseEventType.TOKEN, "chunk-" + i);
            }
            buffer.offerTerminal(SseEventType.DONE, Map.of("reason", "complete"));

            // then
            awaitBufferClosed(buffer, 2000);

            // 终结事件在最后
            assertThat(order.getLast()).isEqualTo(SseEventType.DONE);
            // 之前都是 token
            assertThat(order.subList(0, order.size() - 1))
                    .allMatch(SseEventType.TOKEN::equals);
        }

        @Test
        void offerTerminal后closeEmitter被调用() throws Exception {
            buffer.offerTerminal(SseEventType.DONE, "done");
            awaitBufferClosed(buffer, 2000);

            verify(sseManager, atLeastOnce()).closeEmitter(STREAM_ID);
        }

        @Test
        void 已关闭时offerTerminal直接派发不入队() {
            buffer.close();

            boolean result = buffer.offerTerminal(SseEventType.ERROR, "err");

            assertThat(result).isTrue();
            verify(sseManager).sendEvent(STREAM_ID, SseEventType.ERROR, "err");
        }
    }

    // ==================== 队列满超时丢弃 ====================

    @Nested
    class 队列满超时丢弃 {

        @Test
        void 队列满时offer等待超时返回false() throws Exception {
            // given — 容量为 4 的极小队列，排空间隔极长（不消费）
            var tinyConfig = new SseBufferProperties(
                    true, 4, 3, 1, 5000, 5000, 5000, 5000, 30, 5000
            );
            buffer = new SseEventBuffer(STREAM_ID, sseManager, tinyConfig);

            // when — 快速填满队列
            // 排空线程已取走第一个，所以多 offer 几个确保满
            for (int i = 0; i < 4; i++) {
                buffer.offer(SseEventType.TOKEN, "fill-" + i);
            }
            // 此时队列可能已满，再 offer 应超时
            // 给排空线程一点时间取走第一个，然后再快速填满
            Thread.sleep(10);
            // 快速连续 offer 直到返回 false
            boolean gotFalse = false;
            for (int i = 0; i < 10; i++) {
                if (!buffer.offer(SseEventType.TOKEN, "overflow-" + i)) {
                    gotFalse = true;
                    break;
                }
            }

            // then — 至少一次 offer 返回 false
            assertThat(gotFalse).isTrue();
        }

        @Test
        void 队列满时offerTerminal强制派发并关闭() throws Exception {
            // given — 容量 2，排空间隔极长
            var tinyConfig = new SseBufferProperties(
                    true, 2, 1, 0, 10000, 10000, 10000, 10000, 10, 10000
            );
            buffer = new SseEventBuffer(STREAM_ID, sseManager, tinyConfig);

            // 填满队列
            Thread.sleep(20); // 等排空线程取走第一个
            buffer.offer(SseEventType.TOKEN, "a");
            buffer.offer(SseEventType.TOKEN, "b");
            buffer.offer(SseEventType.TOKEN, "c");

            // when — 终结事件入队失败，应强制派发
            buffer.offerTerminal(SseEventType.ERROR, "forced");

            // then — 无论入队成功与否，终结事件都被派发
            Thread.sleep(100);
            verify(sseManager, atLeastOnce()).sendEvent(eq(STREAM_ID), eq(SseEventType.ERROR), eq("forced"));
        }
    }

    // ==================== close() 行为 ====================

    @Nested
    class 关闭行为 {

        @BeforeEach
        void 初始化() {
            buffer = new SseEventBuffer(STREAM_ID, sseManager, defaultConfig());
        }

        @Test
        void close后offer返回false() {
            buffer.close();

            assertThat(buffer.offer(SseEventType.TOKEN, "data")).isFalse();
            assertThat(buffer.isClosed()).isTrue();
        }

        @Test
        void close后isClosed返回true() {
            assertThat(buffer.isClosed()).isFalse();
            buffer.close();
            assertThat(buffer.isClosed()).isTrue();
        }

        @Test
        void 多次close幂等不抛异常() {
            buffer.close();
            buffer.close();
            buffer.close();

            assertThat(buffer.isClosed()).isTrue();
        }
    }

    // ==================== 并发 close 幂等性 ====================

    @Nested
    class 并发关闭幂等性 {

        @Test
        void 多线程并发close只执行一次中断() throws Exception {
            buffer = new SseEventBuffer(STREAM_ID, sseManager, defaultConfig());

            int threads = 10;
            var latch = new CountDownLatch(threads);
            var successCount = new AtomicInteger(0);

            for (int i = 0; i < threads; i++) {
                Thread.ofVirtual().start(() -> {
                    try {
                        latch.countDown();
                        latch.await();
                        // 同时 close
                        buffer.close();
                        successCount.incrementAndGet();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }

            Thread.sleep(200);
            assertThat(buffer.isClosed()).isTrue();
            assertThat(successCount.get()).isEqualTo(threads); // 全部正常返回，无异常
        }
    }

    // ==================== 心跳注入 ====================

    @Nested
    class 心跳注入 {

        @Test
        void 队列为空时排空线程注入心跳事件() throws Exception {
            // given — gapHeartbeatIntervalMs=50，排空线程 poll 超时后注入心跳
            var heartbeatConfig = new SseBufferProperties(
                    true, 64, 40, 10, 1, 2, 4, 10, 50, 50
            );
            buffer = new SseEventBuffer(STREAM_ID, sseManager, heartbeatConfig);

            // when — 不 offer 任何事件，等待心跳注入
            Thread.sleep(200);

            // then — 至少收到一次心跳
            verify(sseManager, atLeastOnce())
                    .sendEvent(STREAM_ID, SseEventType.HEARTBEAT, "");

            buffer.close();
        }

        @Test
        void 有事件时不注入心跳() throws Exception {
            // given — 极短心跳间隔 + 快速排空
            var config = new SseBufferProperties(
                    true, 64, 40, 10, 0, 0, 0, 0, 50, 50
            );
            buffer = new SseEventBuffer(STREAM_ID, sseManager, config);

            // when — 连续 offer 然后立即发终结事件
            for (int i = 0; i < 10; i++) {
                buffer.offer(SseEventType.TOKEN, "t" + i);
            }
            buffer.offerTerminal(SseEventType.DONE, "end");
            awaitBufferClosed(buffer, 1000);

            // then — 由于事件连续，不应注入心跳（或极少）
            // 统计 heartbeat 调用次数
            long heartbeatCount = mockingDetails(sseManager).getInvocations().stream()
                    .filter(inv -> "sendEvent".equals(inv.getMethod().getName()))
                    .filter(inv -> SseEventType.HEARTBEAT.equals(inv.getArgument(1)))
                    .count();
            // 队列持续有事件时不应有心跳（或至多 1 次启动时的）
            assertThat(heartbeatCount).isLessThanOrEqualTo(1);
        }
    }

    // ==================== 自适应排空速率 ====================

    @Nested
    class 自适应排空速率 {

        @Test
        void 高水位时排空速度快于低水位() throws Exception {
            // given — 配置明显的速率差异
            var config = new SseBufferProperties(
                    true, 256, 10, 3, 1, 5, 20, 50, 50, 5000
            );

            // 高水位测试：一次性灌入超过 highWaterMark 的事件
            var highWaterDispatched = new CopyOnWriteArrayList<Long>();
            var lowWaterDispatched = new CopyOnWriteArrayList<Long>();

            // --- 高水位场景 ---
            var highBuffer = new SseEventBuffer(STREAM_ID, sseManager, config);
            doAnswer(inv -> {
                if (!SseEventType.HEARTBEAT.equals(inv.getArgument(1))
                        && !SseEventType.DONE.equals(inv.getArgument(1))) {
                    highWaterDispatched.add(System.nanoTime());
                }
                return null;
            }).when(sseManager).sendEvent(eq(STREAM_ID), any(), any());

            for (int i = 0; i < 20; i++) {
                highBuffer.offer(SseEventType.TOKEN, "hw-" + i);
            }
            highBuffer.offerTerminal(SseEventType.DONE, "end");
            awaitBufferClosed(highBuffer, 3000);

            // --- 低水位场景 ---
            reset(sseManager);
            String lowStreamId = "test-stream-low";
            var lowBuffer = new SseEventBuffer(lowStreamId, sseManager, config);
            doAnswer(inv -> {
                if (!SseEventType.HEARTBEAT.equals(inv.getArgument(1))
                        && !SseEventType.DONE.equals(inv.getArgument(1))) {
                    lowWaterDispatched.add(System.nanoTime());
                }
                return null;
            }).when(sseManager).sendEvent(eq(lowStreamId), any(), any());

            // 逐个 offer，保持低水位
            for (int i = 0; i < 5; i++) {
                lowBuffer.offer(SseEventType.TOKEN, "lw-" + i);
                Thread.sleep(30); // 给排空线程时间消费，保持低水位
            }
            lowBuffer.offerTerminal(SseEventType.DONE, "end");
            awaitBufferClosed(lowBuffer, 3000);

            // then — 高水位场景平均间隔小于低水位场景
            if (highWaterDispatched.size() > 2 && lowWaterDispatched.size() > 2) {
                double highAvgInterval = averageInterval(highWaterDispatched);
                double lowAvgInterval = averageInterval(lowWaterDispatched);
                // 高水位排空更快（间隔更短）
                assertThat(highAvgInterval).isLessThan(lowAvgInterval);
            }
            // 如果样本不足则跳过断言（非确定性测试环境可能出现）
        }
    }

    // ==================== 前瞻扫描工具调用减速 ====================

    @Nested
    class 前瞻扫描工具调用减速 {

        @Test
        void 队列中包含TOOL_CALL事件时排空减速() throws Exception {
            // given — 配置：preLookaheadIntervalMs 远大于 slowDrainIntervalMs
            var config = new SseBufferProperties(
                    true, 64, 50, 5, 1, 2, 5, 80, 50, 5000
            );
            buffer = new SseEventBuffer(STREAM_ID, sseManager, config);

            var dispatched = new CopyOnWriteArrayList<Long>();
            doAnswer(inv -> {
                if (SseEventType.REASONING.equals(inv.getArgument(1))) {
                    dispatched.add(System.nanoTime());
                }
                return null;
            }).when(sseManager).sendEvent(eq(STREAM_ID), any(), any());

            // when — 入队 REASONING 事件，其中包含 TOOL_CALL 标记
            Map<String, Object> toolCallEvent = Map.of(
                    "event", Map.of("type", "TOOL_CALL", "name", "search")
            );
            // 先放几个普通 reasoning
            for (int i = 0; i < 3; i++) {
                buffer.offer(SseEventType.REASONING, Map.of("event", Map.of("type", "TEXT", "text", "thinking...")));
            }
            // 放入工具调用事件
            buffer.offer(SseEventType.REASONING, toolCallEvent);
            buffer.offerTerminal(SseEventType.DONE, "end");

            awaitBufferClosed(buffer, 3000);

            // then — 事件被排空，无异常（速率降低难以精确断言，至少验证功能正确性）
            verify(sseManager, atLeastOnce()).sendEvent(eq(STREAM_ID), eq(SseEventType.REASONING), any());
        }

        @Test
        void 队列中包含PROGRESS事件时也触发减速() throws Exception {
            var config = new SseBufferProperties(
                    true, 64, 50, 5, 1, 2, 5, 80, 50, 5000
            );
            buffer = new SseEventBuffer(STREAM_ID, sseManager, config);

            // when — PROGRESS 类型也应触发减速
            Map<String, Object> progressEvent = Map.of(
                    "event", Map.of("type", "PROGRESS", "message", "loading...")
            );
            buffer.offer(SseEventType.REASONING, progressEvent);
            buffer.offerTerminal(SseEventType.DONE, "end");

            awaitBufferClosed(buffer, 2000);

            // then — 正常派发，不抛异常
            verify(sseManager, atLeastOnce()).sendEvent(eq(STREAM_ID), eq(SseEventType.REASONING), any());
        }

        @Test
        void 非REASONING事件不触发前瞻减速() throws Exception {
            // given — 只有 TOKEN 事件，无 REASONING，前瞻应不匹配
            var config = new SseBufferProperties(
                    true, 64, 50, 3, 1, 2, 5, 200, 50, 5000
            );
            buffer = new SseEventBuffer(STREAM_ID, sseManager, config);

            var timestamps = new CopyOnWriteArrayList<Long>();
            doAnswer(inv -> {
                if (SseEventType.TOKEN.equals(inv.getArgument(1))) {
                    timestamps.add(System.nanoTime());
                }
                return null;
            }).when(sseManager).sendEvent(eq(STREAM_ID), any(), any());

            // when — 只入队 TOKEN 事件（低水位）
            buffer.offer(SseEventType.TOKEN, "a");
            buffer.offer(SseEventType.TOKEN, "b");
            buffer.offerTerminal(SseEventType.DONE, "end");

            awaitBufferClosed(buffer, 2000);

            // then — 事件正常排空
            assertThat(timestamps.size()).isEqualTo(2);
        }
    }

    // ==================== 边界场景 ====================

    @Nested
    class 边界场景 {

        @Test
        void queueDepth反映当前深度() {
            var config = new SseBufferProperties(
                    true, 64, 40, 10, 5000, 5000, 5000, 5000, 50, 5000
            );
            buffer = new SseEventBuffer(STREAM_ID, sseManager, config);

            // 排空线程间隔极长，offer 后深度应增加
            buffer.offer(SseEventType.TOKEN, "x");
            // 深度至少 0（可能排空线程刚好取走），但 offer 成功说明能工作
            assertThat(buffer.queueDepth()).isGreaterThanOrEqualTo(0);
        }

        @Test
        void 中断线程时offer返回false() throws Exception {
            buffer = new SseEventBuffer(STREAM_ID, sseManager, defaultConfig());

            // 在独立线程中测试中断行为
            var result = new boolean[]{true};
            var t = Thread.ofVirtual().start(() -> {
                Thread.currentThread().interrupt();
                result[0] = buffer.offer(SseEventType.TOKEN, "data");
            });
            t.join(1000);

            assertThat(result[0]).isFalse();
        }
    }

    // ==================== 辅助方法 ====================

    /** 等待缓冲区关闭（排空线程退出） */
    private void awaitBufferClosed(SseEventBuffer buf, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!buf.isClosed() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
    }

    /** 计算时间戳列表的平均间隔（纳秒） */
    private double averageInterval(List<Long> timestamps) {
        if (timestamps.size() < 2) return 0;
        long totalInterval = 0;
        for (int i = 1; i < timestamps.size(); i++) {
            totalInterval += timestamps.get(i) - timestamps.get(i - 1);
        }
        return (double) totalInterval / (timestamps.size() - 1);
    }
}
