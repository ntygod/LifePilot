package com.lifepilot.memory.store.workspace;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 工作区清理任务。
 *
 * @author zsg
 * @since 2026-03-20
 */
public class WorkspaceCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceCleanupJob.class);

    private final SessionWorkspaceService workspaceService;

    public WorkspaceCleanupJob(SessionWorkspaceService workspaceService) {
        this.workspaceService = workspaceService;
    }

    @Scheduled(cron = "${lifepilot.memory.workspace.cleanup-cron:0 */5 * * * *}")
    public void cleanup() {
        int expired = workspaceService.expireDueItems();
        int purged = workspaceService.purgeTerminalItems();
        if (expired > 0 || purged > 0) {
            log.info("工作区清理完成: expired={}, purged={}", expired, purged);
        }
    }
}
