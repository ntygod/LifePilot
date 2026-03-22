/**
 * Agent 引擎核心：ReAct 循环控制、不可变状态管理、上下文工程。
 *
 * <p>包含 ReactAgentLoop、ReactAgentState、ContextAssembler 等核心组件，
 * 负责 LLM 决策（概率性）与状态转换（确定性）的严格分离。
 */
package com.lifepilot.agent;
