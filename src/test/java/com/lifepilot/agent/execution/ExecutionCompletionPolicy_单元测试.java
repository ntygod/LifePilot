package com.lifepilot.agent.execution;

import com.lifepilot.agent.execution.ExecutionCompletionPolicy.CompletionDisposition;
import com.lifepilot.agent.execution.ExecutionCompletionPolicy.CompletionEvaluation;
import com.lifepilot.agent.model.*;
import com.lifepilot.agent.suspend.model.ResumePayload;
import com.lifepilot.interaction.model.InteractionSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ExecutionCompletionPolicy 单元测试。
 *
 * <p>覆盖 evaluate() 方法的全部决策路径：await_user_input 协议、
 * completion_control 标签、显式前缀识别、隐式终止拦截、直接回答回退。</p>
 *
 * @author zsg
 * @since 2026-04-03
 */
class ExecutionCompletionPolicy_单元测试 {

    private ExecutionCompletionPolicy policy;

    @BeforeEach
    void 初始化() {
        policy = new ExecutionCompletionPolicy();
    }

    // ========== 辅助方法 ==========

    /** 创建最简 AgentRequest（AUTO 模式）。 */
    private AgentRequest 简单请求() {
        return new AgentRequest("测试消息", "session-1", "web");
    }

    /** 创建 EXECUTION 模式请求。 */
    private AgentRequest 执行模式请求() {
        return new AgentRequest("执行任务", "session-1",
                InteractionSource.legacy("web", "session-1"),
                null, AgentTaskMode.EXECUTION, null, null, null, 0, null, null, null, null);
    }

    /** 创建默认预算。 */
    private Budget 默认预算() {
        return Budget.builder()
                .maxTokens(10000).tokensUsed(0).tokensReserved(0)
                .maxSteps(20).stepsUsed(0)
                .maxDuration(Duration.ofMinutes(5)).elapsed(Duration.ZERO)
                .build();
    }

    /** 创建空步骤的基础 state（AUTO 模式）。 */
    private ReactAgentState 空步骤状态() {
        return ReactAgentState.builder()
                .traceId("trace-1").sessionId("session-1").goal("测试")
                .source(InteractionSource.legacy("web", "session-1"))
                .taskMode(AgentTaskMode.AUTO)
                .steps(List.of())
                .stepCount(0)
                .shortTermMemory(List.of())
                .mentionedEntities(List.of())
                .budget(默认预算())
                .depth(0).done(false)
                .completionMode(CompletionMode.NORMAL)
                .earlyStopRejectCount(0).suspended(false)
                .build();
    }

    /** 创建 EXECUTION 模式的状态。 */
    private ReactAgentState 执行模式状态() {
        return 空步骤状态().toBuilder()
                .taskMode(AgentTaskMode.EXECUTION)
                .build();
    }

    /** 创建带 Suspend 步骤的状态（标明进入过挂起/恢复阶段）。 */
    private ReactAgentState 含挂起步骤状态() {
        var suspendStep = new ReactStep.Suspend(
                new SuspendReason.ScheduledWakeup(Instant.now(), "等待数据"),
                Instant.now(), 0);
        return 空步骤状态().toBuilder()
                .steps(List.of(suspendStep))
                .stepCount(1)
                .build();
    }

    /** 创建带 Resume 步骤的状态。 */
    private ReactAgentState 含恢复步骤状态() {
        var resumeStep = new ReactStep.Resume(
                new ResumePayload.WakeupSignal(Instant.now()),
                Instant.now(), Duration.ofSeconds(30));
        return 空步骤状态().toBuilder()
                .steps(List.of(resumeStep))
                .stepCount(1)
                .build();
    }

    // ========== await_user_input 协议测试 ==========

    @Nested
    class AwaitUserInput协议 {

        @Test
        void 包含await标签时挂起等待用户输入() {
            String content = "我需要更多信息。<await_user_input>请提供您的项目名称</await_user_input>";

            CompletionEvaluation result = policy.evaluate(简单请求(), 空步骤状态(), content);

            assertThat(result.disposition()).isEqualTo(CompletionDisposition.SUSPEND_FOR_USER_INPUT);
            assertThat(result.completionReason()).isEqualTo(CompletionReason.SUSPENDED);
            assertThat(result.terminationReason()).isEqualTo("await_user_input");
            assertThat(result.suspendPrompt()).isEqualTo("请提供您的项目名称");
        }

        @Test
        void 标签大小写不敏感() {
            String content = "<AWAIT_USER_INPUT>请确认操作</AWAIT_USER_INPUT>";

            CompletionEvaluation result = policy.evaluate(简单请求(), 空步骤状态(), content);

            assertThat(result.disposition()).isEqualTo(CompletionDisposition.SUSPEND_FOR_USER_INPUT);
            assertThat(result.suspendPrompt()).isEqualTo("请确认操作");
        }

        @Test
        void 标签内有空白时正确提取并去除空白() {
            String content = "<await_user_input>  请输入配置参数  </await_user_input>";

            CompletionEvaluation result = policy.evaluate(简单请求(), 空步骤状态(), content);

            assertThat(result.disposition()).isEqualTo(CompletionDisposition.SUSPEND_FOR_USER_INPUT);
            assertThat(result.suspendPrompt()).isEqualTo("请输入配置参数");
        }

        @Test
        void 标签内容为空白时不触发挂起() {
            String content = "正常内容 <await_user_input>   </await_user_input> 后续";

            CompletionEvaluation result = policy.evaluate(简单请求(), 空步骤状态(), content);

            // 空白 prompt 不触发挂起，走后续逻辑
            assertThat(result.disposition()).isNotEqualTo(CompletionDisposition.SUSPEND_FOR_USER_INPUT);
        }

        @Test
        void 可见内容移除标签后保留前后文() {
            String content = "分析完毕。<await_user_input>请确认是否继续</await_user_input>谢谢。";

            CompletionEvaluation result = policy.evaluate(简单请求(), 空步骤状态(), content);

            assertThat(result.disposition()).isEqualTo(CompletionDisposition.SUSPEND_FOR_USER_INPUT);
            // visibleContent 应去掉标签，保留前后文
            assertThat(result.userVisibleContent()).contains("分析完毕");
            assertThat(result.userVisibleContent()).doesNotContain("await_user_input");
        }

        @Test
        void 仅有标签没有前后文时可见内容等于prompt() {
            String content = "<await_user_input>需要更多上下文</await_user_input>";

            CompletionEvaluation result = policy.evaluate(简单请求(), 空步骤状态(), content);

            assertThat(result.disposition()).isEqualTo(CompletionDisposition.SUSPEND_FOR_USER_INPUT);
            assertThat(result.userVisibleContent()).isEqualTo("需要更多上下文");
            assertThat(result.suspendPrompt()).isEqualTo("需要更多上下文");
        }

        @Test
        void await优先级高于completion_control() {
            // 同时包含 await 和 completion_control 时，await 优先
            String content = "<await_user_input>请确认</await_user_input><completion_control>done</completion_control>";

            CompletionEvaluation result = policy.evaluate(简单请求(), 空步骤状态(), content);

            assertThat(result.disposition()).isEqualTo(CompletionDisposition.SUSPEND_FOR_USER_INPUT);
        }

        @Test
        void 标签内含换行时正确提取() {
            String content = "<await_user_input>\n请提供以下信息：\n1. 姓名\n2. 电话\n</await_user_input>";

            CompletionEvaluation result = policy.evaluate(简单请求(), 空步骤状态(), content);

            assertThat(result.disposition()).isEqualTo(CompletionDisposition.SUSPEND_FOR_USER_INPUT);
            assertThat(result.suspendPrompt()).contains("请提供以下信息");
        }
    }

    // ========== completion_control 标签测试 ==========

    @Nested
    class CompletionControl标签 {

        @Test
        void done指令触发显式完成() {
            String content = "任务已经全部完成。<completion_control>done</completion_control>";

            CompletionEvaluation result = policy.evaluate(简单请求(), 空步骤状态(), content);

            assertThat(result.disposition()).isEqualTo(CompletionDisposition.EXPLICIT_TERMINAL);
            assertThat(result.completionReason()).isEqualTo(CompletionReason.EXPLICIT_COMPLETED);
            assertThat(result.userVisibleContent()).isEqualTo("任务已经全部完成。");
            assertThat(result.terminationReason()).isNull();
        }

        @Test
        void blocked指令触发显式阻塞() {
            String content = "已阻塞：缺少必需的 API 密钥<completion_control>blocked</completion_control>";

            CompletionEvaluation result = policy.evaluate(简单请求(), 空步骤状态(), content);

            assertThat(result.disposition()).isEqualTo(CompletionDisposition.EXPLICIT_TERMINAL);
            assertThat(result.completionReason()).isEqualTo(CompletionReason.EXPLICIT_BLOCKED);
            // terminationReason 从 visibleContent 中提取阻塞原因
            assertThat(result.terminationReason()).isNotNull();
        }

        @Test
        void continue指令拒绝隐式终止() {
            String content = "正在思考下一步...<completion_control>continue</completion_control>";

            CompletionEvaluation result = policy.evaluate(简单请求(), 空步骤状态(), content);

            assertThat(result.disposition()).isEqualTo(CompletionDisposition.REJECT_IMPLICIT_TERMINATION);
            assertThat(result.completionReason()).isNull();
            assertThat(result.userVisibleContent()).isEqualTo("正在思考下一步...");
        }

        @Test
        void 指令大小写不敏感() {
            String content = "<completion_control>DONE</completion_control>";

            CompletionEvaluation result = policy.evaluate(简单请求(), 空步骤状态(), content);

            assertThat(result.disposition()).isEqualTo(CompletionDisposition.EXPLICIT_TERMINAL);
            assertThat(result.completionReason()).isEqualTo(CompletionReason.EXPLICIT_COMPLETED);
        }

        @Test
        void 混合大小写指令正确解析() {
            String content = "任务阻塞。<completion_control>Blocked</completion_control>";

            CompletionEvaluation result = policy.evaluate(简单请求(), 空步骤状态(), content);

            assertThat(result.disposition()).isEqualTo(CompletionDisposition.EXPLICIT_TERMINAL);
            assertThat(result.completionReason()).isEqualTo(CompletionReason.EXPLICIT_BLOCKED);
        }

        @Test
        void 标签内有空白时正确提取指令() {
            String content = "<completion_control>  done  </completion_control>";

            CompletionEvaluation result = policy.evaluate(简单请求(), 空步骤状态(), content);

            assertThat(result.disposition()).isEqualTo(CompletionDisposition.EXPLICIT_TERMINAL);
            assertThat(result.completionReason()).isEqualTo(CompletionReason.EXPLICIT_COMPLETED);
        }

        @Test
        void done指令可见内容去除标签() {
            String content = "所有文件已处理完毕。<completion_control>done</completion_control>";

            CompletionEvaluation result = policy.evaluate(简单请求(), 空步骤状态(), content);

            assertThat(result.userVisibleContent()).isEqualTo("所有文件已处理完毕。");
            assertThat(result.userVisibleContent()).doesNotContain("completion_control");
        }

        @Test
        void blocked指令的阻塞原因包含已阻塞前缀时提取原因文本() {
            String content = "已阻塞：权限不足<completion_control>blocked</completion_control>";

            CompletionEvaluation result = policy.evaluate(简单请求(), 空步骤状态(), content);

            assertThat(result.terminationReason()).isEqualTo("权限不足");
        }

        @Test
        void blocked指令可见内容无已阻塞前缀时直接使用内容作为原因() {
            String content = "无法连接数据库<completion_control>blocked</completion_control>";

            CompletionEvaluation result = policy.evaluate(简单请求(), 空步骤状态(), content);

            assertThat(result.terminationReason()).isEqualTo("无法连接数据库");
        }
    }

    // ========== 显式前缀识别测试 ==========

    @Nested
    class 显式前缀识别 {

        @Test
        void 已完成中文冒号前缀识别为完成() {
            String content = "已完成：所有步骤都已执行成功。";

            CompletionEvaluation result = policy.evaluate(简单请求(), 空步骤状态(), content);

            assertThat(result.disposition()).isEqualTo(CompletionDisposition.EXPLICIT_TERMINAL);
            assertThat(result.completionReason()).isEqualTo(CompletionReason.EXPLICIT_COMPLETED);
            assertThat(result.terminationReason()).isNull();
        }

        @Test
        void 已完成英文冒号前缀识别为完成() {
            String content = "已完成:数据已同步。";

            CompletionEvaluation result = policy.evaluate(简单请求(), 空步骤状态(), content);

            assertThat(result.disposition()).isEqualTo(CompletionDisposition.EXPLICIT_TERMINAL);
            assertThat(result.completionReason()).isEqualTo(CompletionReason.EXPLICIT_COMPLETED);
        }

        @Test
        void completed英文前缀识别为完成() {
            String content = "completed: all tasks finished.";

            CompletionEvaluation result = policy.evaluate(简单请求(), 空步骤状态(), content);

            assertThat(result.disposition()).isEqualTo(CompletionDisposition.EXPLICIT_TERMINAL);
            assertThat(result.completionReason()).isEqualTo(CompletionReason.EXPLICIT_COMPLETED);
        }

        @Test
        void completed大小写不敏感() {
            String content = "Completed: all good.";

            CompletionEvaluation result = policy.evaluate(简单请求(), 空步骤状态(), content);

            assertThat(result.disposition()).isEqualTo(CompletionDisposition.EXPLICIT_TERMINAL);
            assertThat(result.completionReason()).isEqualTo(CompletionReason.EXPLICIT_COMPLETED);
        }

        @Test
        void 已阻塞中文冒号前缀识别为阻塞() {
            String content = "已阻塞：缺少写入权限";

            CompletionEvaluation result = policy.evaluate(简单请求(), 空步骤状态(), content);

            assertThat(result.disposition()).isEqualTo(CompletionDisposition.EXPLICIT_TERMINAL);
            assertThat(result.completionReason()).isEqualTo(CompletionReason.EXPLICIT_BLOCKED);
            assertThat(result.terminationReason()).isEqualTo("缺少写入权限");
        }

        @Test
        void 已阻塞英文冒号前缀识别为阻塞() {
            String content = "已阻塞:网络不可达";

            CompletionEvaluation result = policy.evaluate(简单请求(), 空步骤状态(), content);

            assertThat(result.disposition()).isEqualTo(CompletionDisposition.EXPLICIT_TERMINAL);
            assertThat(result.completionReason()).isEqualTo(CompletionReason.EXPLICIT_BLOCKED);
            assertThat(result.terminationReason()).isEqualTo("网络不可达");
        }

        @Test
        void blocked英文前缀识别为阻塞() {
            String content = "blocked: missing API key";

            CompletionEvaluation result = policy.evaluate(简单请求(), 空步骤状态(), content);

            assertThat(result.disposition()).isEqualTo(CompletionDisposition.EXPLICIT_TERMINAL);
            assertThat(result.completionReason()).isEqualTo(CompletionReason.EXPLICIT_BLOCKED);
            assertThat(result.terminationReason()).isEqualTo("missing API key");
        }

        @Test
        void blocked大小写不敏感() {
            String content = "BLOCKED：系统限制";

            CompletionEvaluation result = policy.evaluate(简单请求(), 空步骤状态(), content);

            assertThat(result.disposition()).isEqualTo(CompletionDisposition.EXPLICIT_TERMINAL);
            assertThat(result.completionReason()).isEqualTo(CompletionReason.EXPLICIT_BLOCKED);
        }

        @Test
        void 前缀前有空白时仍能识别() {
            String content = "  已完成：任务完毕";

            CompletionEvaluation result = policy.evaluate(简单请求(), 空步骤状态(), content);

            assertThat(result.disposition()).isEqualTo(CompletionDisposition.EXPLICIT_TERMINAL);
            assertThat(result.completionReason()).isEqualTo(CompletionReason.EXPLICIT_COMPLETED);
        }

        @Test
        void 已阻塞但冒号后内容为空时返回默认原因() {
            String content = "已阻塞：";

            CompletionEvaluation result = policy.evaluate(简单请求(), 空步骤状态(), content);

            assertThat(result.disposition()).isEqualTo(CompletionDisposition.EXPLICIT_TERMINAL);
            assertThat(result.completionReason()).isEqualTo(CompletionReason.EXPLICIT_BLOCKED);
            assertThat(result.terminationReason()).isEqualTo("任务已阻塞");
        }

        @Test
        void completion_control优先于显式前缀() {
            // completion_control 在代码中先检查
            String content = "已完成：任务完成 <completion_control>blocked</completion_control>";

            CompletionEvaluation result = policy.evaluate(简单请求(), 空步骤状态(), content);

            // completion_control blocked 优先
            assertThat(result.disposition()).isEqualTo(CompletionDisposition.EXPLICIT_TERMINAL);
            assertThat(result.completionReason()).isEqualTo(CompletionReason.EXPLICIT_BLOCKED);
        }
    }

    // ========== 隐式终止拦截测试 ==========

    @Nested
    class 隐式终止拦截 {

        @Test
        void 执行模式下普通文本被拒绝终止() {
            String content = "我正在分析这个问题，接下来需要检查日志。";

            CompletionEvaluation result = policy.evaluate(执行模式请求(), 执行模式状态(), content);

            assertThat(result.disposition()).isEqualTo(CompletionDisposition.REJECT_IMPLICIT_TERMINATION);
            assertThat(result.completionReason()).isNull();
        }

        @Test
        void AUTO模式含挂起步骤时拒绝隐式终止() {
            // hasSuspendResumeEvidence 为 true
            String content = "分析进展报告";

            CompletionEvaluation result = policy.evaluate(简单请求(), 含挂起步骤状态(), content);

            assertThat(result.disposition()).isEqualTo(CompletionDisposition.REJECT_IMPLICIT_TERMINATION);
        }

        @Test
        void AUTO模式含恢复步骤时拒绝隐式终止() {
            String content = "继续处理恢复后的任务";

            CompletionEvaluation result = policy.evaluate(简单请求(), 含恢复步骤状态(), content);

            assertThat(result.disposition()).isEqualTo(CompletionDisposition.REJECT_IMPLICIT_TERMINATION);
        }

        @Test
        void 执行模式下空白文本不拒绝终止而是直接回答() {
            // normalizeTerminationText 对空白内容返回 ""，shouldRejectImplicitTermination 返回 false
            String content = "   ";

            CompletionEvaluation result = policy.evaluate(执行模式请求(), 执行模式状态(), content);

            assertThat(result.disposition()).isEqualTo(CompletionDisposition.DIRECT_ANSWER);
        }

        @Test
        void 执行模式下显式完成前缀不会被拦截() {
            // 显式前缀在 shouldRejectImplicitTermination 之前被处理
            String content = "已完成：所有任务执行完毕";

            CompletionEvaluation result = policy.evaluate(执行模式请求(), 执行模式状态(), content);

            assertThat(result.disposition()).isEqualTo(CompletionDisposition.EXPLICIT_TERMINAL);
            assertThat(result.completionReason()).isEqualTo(CompletionReason.EXPLICIT_COMPLETED);
        }

        @Test
        void 仅有ToolCall步骤不算挂起恢复证据() {
            var toolCallStep = new ReactStep.ToolCall("search", "搜索", "{}", 100);
            var state = 空步骤状态().toBuilder()
                    .steps(List.of(toolCallStep))
                    .stepCount(1)
                    .build();
            String content = "搜索结果如下";

            CompletionEvaluation result = policy.evaluate(简单请求(), state, content);

            // AUTO 模式、无挂起/恢复证据 -> 直接回答
            assertThat(result.disposition()).isEqualTo(CompletionDisposition.DIRECT_ANSWER);
        }

        @Test
        void 混合步骤中含Suspend时触发拦截() {
            var toolCallStep = new ReactStep.ToolCall("search", "搜索", "{}", 100);
            var suspendStep = new ReactStep.Suspend(
                    new SuspendReason.ScheduledWakeup(Instant.now(), "等待"),
                    Instant.now(), 1);
            var state = 空步骤状态().toBuilder()
                    .steps(List.of(toolCallStep, suspendStep))
                    .stepCount(2)
                    .build();
            String content = "继续下一步操作";

            CompletionEvaluation result = policy.evaluate(简单请求(), state, content);

            assertThat(result.disposition()).isEqualTo(CompletionDisposition.REJECT_IMPLICIT_TERMINATION);
        }
    }

    // ========== 直接回答回退测试 ==========

    @Nested
    class 直接回答回退 {

        @Test
        void AUTO模式无特殊标签和前缀时返回直接回答() {
            String content = "今天天气不错，适合出门。";

            CompletionEvaluation result = policy.evaluate(简单请求(), 空步骤状态(), content);

            assertThat(result.disposition()).isEqualTo(CompletionDisposition.DIRECT_ANSWER);
            assertThat(result.completionReason()).isEqualTo(CompletionReason.DIRECT_ANSWER);
            assertThat(result.terminationReason()).isNull();
            assertThat(result.userVisibleContent()).isEqualTo(content);
            assertThat(result.suspendPrompt()).isNull();
        }

        @Test
        void ANSWER模式普通文本返回直接回答() {
            var answerState = 空步骤状态().toBuilder()
                    .taskMode(AgentTaskMode.ANSWER)
                    .build();
            String content = "这是一个简单问答回复。";

            CompletionEvaluation result = policy.evaluate(简单请求(), answerState, content);

            assertThat(result.disposition()).isEqualTo(CompletionDisposition.DIRECT_ANSWER);
            assertThat(result.completionReason()).isEqualTo(CompletionReason.DIRECT_ANSWER);
        }
    }

    // ========== null 和空白 content 边界测试 ==========

    @Nested
    class 空值和空白边界 {

        @ParameterizedTest(name = "content={0}")
        @NullAndEmptySource
        @ValueSource(strings = {"  ", "\t", "\n", " \r\n "})
        void null或空白内容在AUTO模式下返回直接回答(String content) {
            CompletionEvaluation result = policy.evaluate(简单请求(), 空步骤状态(), content);

            assertThat(result.disposition()).isEqualTo(CompletionDisposition.DIRECT_ANSWER);
            assertThat(result.completionReason()).isEqualTo(CompletionReason.DIRECT_ANSWER);
        }

        @ParameterizedTest(name = "content={0}")
        @NullAndEmptySource
        @ValueSource(strings = {"  ", "\t"})
        void null或空白内容在执行模式下也返回直接回答(String content) {
            // 执行模式下空白内容不会被 shouldRejectImplicitTermination 拦截
            CompletionEvaluation result = policy.evaluate(执行模式请求(), 执行模式状态(), content);

            assertThat(result.disposition()).isEqualTo(CompletionDisposition.DIRECT_ANSWER);
        }
    }

    // ========== 决策优先级综合测试 ==========

    @Nested
    class 决策优先级 {

        @Test
        void 优先级顺序_await最高_其次completion_control_再次显式前缀_最后隐式拦截() {
            // 1. await_user_input 最优先
            String awaitContent = "<await_user_input>请提供密码</await_user_input>";
            assertThat(policy.evaluate(简单请求(), 空步骤状态(), awaitContent).disposition())
                    .isEqualTo(CompletionDisposition.SUSPEND_FOR_USER_INPUT);

            // 2. completion_control 次之
            String controlContent = "<completion_control>done</completion_control>";
            assertThat(policy.evaluate(简单请求(), 空步骤状态(), controlContent).disposition())
                    .isEqualTo(CompletionDisposition.EXPLICIT_TERMINAL);

            // 3. 显式前缀再次
            String prefixContent = "已完成：全部完成";
            assertThat(policy.evaluate(简单请求(), 空步骤状态(), prefixContent).disposition())
                    .isEqualTo(CompletionDisposition.EXPLICIT_TERMINAL);

            // 4. 执行模式下隐式拦截
            String normalContent = "这是一个阶段性报告";
            assertThat(policy.evaluate(执行模式请求(), 执行模式状态(), normalContent).disposition())
                    .isEqualTo(CompletionDisposition.REJECT_IMPLICIT_TERMINATION);

            // 5. 最终回退到直接回答
            assertThat(policy.evaluate(简单请求(), 空步骤状态(), normalContent).disposition())
                    .isEqualTo(CompletionDisposition.DIRECT_ANSWER);
        }

        @Test
        void await同时有显式前缀时await优先() {
            String content = "已完成：操作完毕 <await_user_input>但还需要确认一个细节</await_user_input>";

            CompletionEvaluation result = policy.evaluate(简单请求(), 空步骤状态(), content);

            assertThat(result.disposition()).isEqualTo(CompletionDisposition.SUSPEND_FOR_USER_INPUT);
        }

        @Test
        void completion_control_continue在执行模式下也生效() {
            String content = "中间进展 <completion_control>continue</completion_control>";

            CompletionEvaluation result = policy.evaluate(执行模式请求(), 执行模式状态(), content);

            assertThat(result.disposition()).isEqualTo(CompletionDisposition.REJECT_IMPLICIT_TERMINATION);
        }

        @Test
        void completion_control_done在执行模式下优先于隐式终止拦截() {
            String content = "任务全部完成 <completion_control>done</completion_control>";

            CompletionEvaluation result = policy.evaluate(执行模式请求(), 执行模式状态(), content);

            assertThat(result.disposition()).isEqualTo(CompletionDisposition.EXPLICIT_TERMINAL);
            assertThat(result.completionReason()).isEqualTo(CompletionReason.EXPLICIT_COMPLETED);
        }
    }

    // ========== extractBlockedReason 辅助逻辑测试（通过 evaluate 间接验证）==========

    @Nested
    class 阻塞原因提取 {

        @Test
        void 已阻塞中文全角冒号提取原因() {
            String content = "已阻塞：第三方服务超时";

            CompletionEvaluation result = policy.evaluate(简单请求(), 空步骤状态(), content);

            assertThat(result.terminationReason()).isEqualTo("第三方服务超时");
        }

        @Test
        void 已阻塞英文冒号提取原因() {
            String content = "已阻塞:磁盘空间不足";

            CompletionEvaluation result = policy.evaluate(简单请求(), 空步骤状态(), content);

            assertThat(result.terminationReason()).isEqualTo("磁盘空间不足");
        }

        @Test
        void blocked英文前缀提取原因() {
            String content = "blocked: insufficient permissions";

            CompletionEvaluation result = policy.evaluate(简单请求(), 空步骤状态(), content);

            assertThat(result.terminationReason()).isEqualTo("insufficient permissions");
        }

        @Test
        void 阻塞前缀后无内容返回默认阻塞原因() {
            String content = "已阻塞：  ";

            CompletionEvaluation result = policy.evaluate(简单请求(), 空步骤状态(), content);

            assertThat(result.terminationReason()).isEqualTo("任务已阻塞");
        }

        @Test
        void blocked全角冒号也能匹配() {
            String content = "blocked：network error";

            CompletionEvaluation result = policy.evaluate(简单请求(), 空步骤状态(), content);

            assertThat(result.disposition()).isEqualTo(CompletionDisposition.EXPLICIT_TERMINAL);
            assertThat(result.completionReason()).isEqualTo(CompletionReason.EXPLICIT_BLOCKED);
            assertThat(result.terminationReason()).isEqualTo("network error");
        }

        @Test
        void completion_control_blocked中已阻塞前缀提取原因() {
            String content = "已阻塞：内存溢出 <completion_control>blocked</completion_control>";

            CompletionEvaluation result = policy.evaluate(简单请求(), 空步骤状态(), content);

            // visibleContent 去掉标签后是 "已阻塞：内存溢出"
            assertThat(result.terminationReason()).isEqualTo("内存溢出");
        }

        @Test
        void completion_control_blocked中无前缀时直接用可见内容() {
            String content = "系统资源不可用 <completion_control>blocked</completion_control>";

            CompletionEvaluation result = policy.evaluate(简单请求(), 空步骤状态(), content);

            assertThat(result.terminationReason()).isEqualTo("系统资源不可用");
        }
    }

    // ========== CompletionEvaluation record 结构验证 ==========

    @Nested
    class EvaluationRecord结构 {

        @Test
        void 直接回答场景字段完整性() {
            CompletionEvaluation result = policy.evaluate(简单请求(), 空步骤状态(), "普通回复");

            assertThat(result.disposition()).isEqualTo(CompletionDisposition.DIRECT_ANSWER);
            assertThat(result.completionReason()).isEqualTo(CompletionReason.DIRECT_ANSWER);
            assertThat(result.terminationReason()).isNull();
            assertThat(result.userVisibleContent()).isEqualTo("普通回复");
            assertThat(result.suspendPrompt()).isNull();
        }

        @Test
        void 挂起场景字段完整性() {
            String content = "前导文字 <await_user_input>你的选择是？</await_user_input>";

            CompletionEvaluation result = policy.evaluate(简单请求(), 空步骤状态(), content);

            assertThat(result.disposition()).isEqualTo(CompletionDisposition.SUSPEND_FOR_USER_INPUT);
            assertThat(result.completionReason()).isEqualTo(CompletionReason.SUSPENDED);
            assertThat(result.terminationReason()).isEqualTo("await_user_input");
            assertThat(result.suspendPrompt()).isEqualTo("你的选择是？");
            assertThat(result.userVisibleContent()).isNotNull();
        }

        @Test
        void 拒绝隐式终止场景字段完整性() {
            String content = "中间报告内容";

            CompletionEvaluation result = policy.evaluate(执行模式请求(), 执行模式状态(), content);

            assertThat(result.disposition()).isEqualTo(CompletionDisposition.REJECT_IMPLICIT_TERMINATION);
            assertThat(result.completionReason()).isNull();
            assertThat(result.terminationReason()).isNull();
            assertThat(result.userVisibleContent()).isEqualTo(content);
            assertThat(result.suspendPrompt()).isNull();
        }
    }
}
