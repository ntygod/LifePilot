1、工作流模块优化 ✅（已完成，spec: workflow-runtime-optimization）

已修复的问题：
- ✅ execute() 同步阻塞 → 新增 WorkflowCommandService（异步非阻塞入口）+ WorkflowRunner（Virtual Thread 后台执行）
- ✅ WaitStep 无自动唤醒 → WaitStep 持久化 wakeUpAt，WakeupScheduler 定时扫表自动恢复
- ✅ ApprovalStep 超时仅重启补偿 → WakeupScheduler 运行时持续检测超时，支持自动批准
- ✅ 触发器生命周期不闭环 → WorkflowRegistry 在 register/enable/disable/热加载时通知 TriggerManager 实时注册/注销
- ✅ cronTasks 以 workflowId 为 key 导致多 Cron 覆盖 → 改为 workflowId#triggerIndex 复合键
- ✅ DagScheduler 有状态 singleton 并发不安全 → 重构为无状态纯函数，所有状态通过参数传入
- ✅ WorkflowContext 可变 HashMap 并发风险 → 底层改为 ConcurrentHashMap
- ✅ WorkflowInstance 缺少阻塞语义字段 → 新增 wakeUpAt/blockedStepId/blockedReason + Flyway V45

新增组件：WorkflowRunner、WorkflowCommandService、WakeupScheduler
废弃方法：WorkflowEngine.execute()（保留但标记 @Deprecated，内部仍被 SubWorkflowStep 使用）

2、eval模块优化

gpt对eval现状的总结：
先说判断：eval 模块方向是对的，定位也清晰，明显想做成"场景化离线评估 + LLM Judge + JUnit/CI 集成"的开发者质量工具。但以当前实现看，它更像一套"框架骨架 + 局部可跑通"的系统，还不适合当严格可靠的回归门禁。
现状
模块分层其实不错：场景定义、五维评估器、LLM Judge、存储、报告、JUnit 扩展都齐了，读起来很完整。核心入口是 EvalEngine.java、TrajectoryEvaluator.java、LlmJudge.java、ScenarioLoader.java、EvalReport.java。从使用面看，它现在主要还是开发者工具，入口基本是 EvalTestExtension.java 和 EvalSuiteExtension.java，我没看到对应的 Web/API 产品入口。

我还跑了 eval 相关测试。结果是 11 个测试里 1 个失败，失败点也很说明问题：集成测试期望 traceId 被带出来，但实际是空字符串。这和当前实现一致，不是偶发现象。

主要问题
最核心的问题是它没有真正评估"真实执行轨迹"。EvalEngine.java (line 90) 到 EvalEngine.java (line 179) 直接自己合成了一串 LlmCallStep，而且注入进来的 TraceRecorder 根本没用上。TrajectoryEvaluator.java (line 63) 还把 traceId 直接置空。结果就是：工具选择、参数合法性、护栏合规这些维度拿不到真实 ToolCallStep / GuardrailStep，评分会系统性失真。

第二个是批量评估的 evalRunId 有明显语义 bug。EvalEngine.java (line 99) 先持久化单场景结果，EvalEngine.java (line 123) 到 EvalEngine.java (line 132) 才在内存里统一覆盖 evalRunId。这会直接让落库数据和报告里的批次 ID 脱节，也会污染 EvalReport.java (line 81) 开始做的退化比较。

第三个是"场景模型很丰富，但执行器没吃进去"。BenchmarkScenario.java (line 37) 到 BenchmarkScenario.java (line 43) 里有 timeoutSeconds、mockToolResponses、initialContext，但 EvalEngine.java (line 87) 现在基本只用了 userInput，再加上 EvalEngine.java (line 197) 的输出 pattern 和 judge criteria。也就是说，文档里说的是"声明式 benchmark"，代码里实际还是"给一句输入跑一次 agent"。

第四个是配置和可用性还有点拧巴。EvalConfigProperties.java (line 20) 说默认启用，但 EvalAutoConfiguration.java (line 47) 又要求显式配置 lifepilot.eval.enabled=true 才会注册。再加上 ScenarioLoader.java (line 50) 默认直接去读场景目录，而我本机默认 ~/.zhiwei/eval/scenarios 目录并不存在，仓库里也没看到开箱即用的示例场景，所以上手成本偏高。

第五个是架构边界重复。eval 里有一套五维评估，TrajectoryEvaluator.java；observability 里又有一套在线/离线轨迹评估，TrajectoryEvaluator.java (line 17)。两边目标不同是可以理解的，但五维逻辑已经明显重复，后面很容易漂移成两套标准。

我的看法
我觉得这个模块值得保留，而且应该保留，但要非常明确它的定位：
eval 应该是"离线 benchmark / 回归门禁 / CI 评估系统"，不是在线运行期评估本体。在线评分继续放 observability，而 eval 负责"拿场景驱动真实 agent 跑一遍，再复用统一的轨迹评估核心"。

如果按这个方向走，我会建议优先级是：

第一优先：接上真实 TraceRecord，不要再造 synthetic steps。
第二优先：修掉 evalRunId 持久化语义，不然批次报告不可信。
第三优先：把 timeoutSeconds / mockToolResponses / initialContext 真正落到执行链里。
第四优先：抽一层共享评估核心，避免和 observability.evaluation 双份维护。
第五优先：补内置样例场景和一个可直接运行的入口，不然它只能算"代码里有模块"。
一句话总结：
现在这套 eval 更像"方向正确但数据基础还不真实"的评测框架。它最该做的不是继续加新维度，而是先把"真实轨迹、真实场景、真实批次"这三件事做扎实。

3、UI重构优化
当前UI存在以下问题：
整体视觉效果不好，缺少质感，流畅感
代码混乱

4、sql脚本整理
经过多次迭代，flyway脚本有点混乱，需要整理。

5、所有功能人工测试通过


6、文档、测试用例补全
