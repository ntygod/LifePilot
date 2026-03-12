package com.lifepilot.workflow.engine;

import com.lifepilot.workflow.model.StepLog;
import com.lifepilot.workflow.model.WorkflowEvent;
import com.lifepilot.workflow.model.WorkflowInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * 工作流实时事件中心，用于 SSE 订阅推送。
 *
 * @author zsg
 * @since 2026-03-10
 */
public class WorkflowRealtimeEventHub {

    private static final Logger log = LoggerFactory.getLogger(WorkflowRealtimeEventHub.class);

    private final ConcurrentHashMap<String, CopyOnWriteArrayList<Consumer<WorkflowInstance>>> workflowExecutionListeners =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<Consumer<WorkflowInstance>>> instanceExecutionListeners =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<Consumer<WorkflowEvent>>> instanceEventListeners =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<Consumer<StepLog>>> instanceStepLogListeners =
            new ConcurrentHashMap<>();

    public AutoCloseable onWorkflowExecution(String workflowId, Consumer<WorkflowInstance> listener) {
        return register(workflowExecutionListeners, workflowId, listener);
    }

    public AutoCloseable onInstanceExecution(String instanceId, Consumer<WorkflowInstance> listener) {
        return register(instanceExecutionListeners, instanceId, listener);
    }

    public AutoCloseable onInstanceEvent(String instanceId, Consumer<WorkflowEvent> listener) {
        return register(instanceEventListeners, instanceId, listener);
    }

    public AutoCloseable onInstanceStepLog(String instanceId, Consumer<StepLog> listener) {
        return register(instanceStepLogListeners, instanceId, listener);
    }

    public void publishExecution(WorkflowInstance instance) {
        notifyListeners(workflowExecutionListeners.get(instance.workflowId()), instance);
        notifyListeners(instanceExecutionListeners.get(instance.id()), instance);
    }

    public void publishEvent(WorkflowEvent event) {
        notifyListeners(instanceEventListeners.get(event.instanceId()), event);
    }

    public void publishStepLog(StepLog stepLog) {
        notifyListeners(instanceStepLogListeners.get(stepLog.instanceId()), stepLog);
    }

    private <T> AutoCloseable register(ConcurrentHashMap<String, CopyOnWriteArrayList<Consumer<T>>> listenersByKey,
                                       String key,
                                       Consumer<T> listener) {
        var listeners = listenersByKey.computeIfAbsent(key, ignored -> new CopyOnWriteArrayList<>());
        listeners.add(listener);
        return () -> {
            listeners.remove(listener);
            if (listeners.isEmpty()) {
                listenersByKey.remove(key, listeners);
            }
        };
    }

    private <T> void notifyListeners(CopyOnWriteArrayList<Consumer<T>> listeners, T payload) {
        if (listeners == null || listeners.isEmpty()) {
            return;
        }
        for (Consumer<T> listener : listeners) {
            try {
                listener.accept(payload);
            } catch (Exception e) {
                log.debug("工作流实时监听器异常: {}", e.getMessage(), e);
            }
        }
    }
}
