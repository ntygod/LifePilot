package com.lifepilot.memory.store.workspace;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * L1 临时工作区配置。
 *
 * @author zsg
 * @since 2026-03-20
 */
@ConfigurationProperties(prefix = "lifepilot.memory.workspace")
public class WorkspaceProperties {

    private boolean enabled = true;
    private int promptMaxItems = 3;
    private int pendingDecisionTtlHours = 72;
    private int taskStateTtlHours = 24;
    private int workingSetTtlHours = 12;
    private int terminalRetentionHours = 72;
    private String cleanupCron = "0 */5 * * * *";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getPromptMaxItems() {
        return promptMaxItems;
    }

    public void setPromptMaxItems(int promptMaxItems) {
        this.promptMaxItems = promptMaxItems;
    }

    public int getPendingDecisionTtlHours() {
        return pendingDecisionTtlHours;
    }

    public void setPendingDecisionTtlHours(int pendingDecisionTtlHours) {
        this.pendingDecisionTtlHours = pendingDecisionTtlHours;
    }

    public int getTaskStateTtlHours() {
        return taskStateTtlHours;
    }

    public void setTaskStateTtlHours(int taskStateTtlHours) {
        this.taskStateTtlHours = taskStateTtlHours;
    }

    public int getWorkingSetTtlHours() {
        return workingSetTtlHours;
    }

    public void setWorkingSetTtlHours(int workingSetTtlHours) {
        this.workingSetTtlHours = workingSetTtlHours;
    }

    public int getTerminalRetentionHours() {
        return terminalRetentionHours;
    }

    public void setTerminalRetentionHours(int terminalRetentionHours) {
        this.terminalRetentionHours = terminalRetentionHours;
    }

    public String getCleanupCron() {
        return cleanupCron;
    }

    public void setCleanupCron(String cleanupCron) {
        this.cleanupCron = cleanupCron;
    }
}
