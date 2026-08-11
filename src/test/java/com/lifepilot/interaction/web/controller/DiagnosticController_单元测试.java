package com.lifepilot.interaction.web.controller;

import com.lifepilot.interaction.web.model.DiagnosticCheckInfo;
import com.lifepilot.interaction.web.model.DiagnosticBundleInfo;
import com.lifepilot.interaction.web.model.DiagnosticReportInfo;
import com.lifepilot.interaction.web.model.LocalBackupFileInfo;
import com.lifepilot.interaction.web.model.LocalBackupInfo;
import com.lifepilot.interaction.web.model.LocalBackupManifestInfo;
import com.lifepilot.interaction.web.model.LocalBackupRestorePlanInfo;
import com.lifepilot.interaction.web.model.LocalBackupRestorePreparationInfo;
import com.lifepilot.interaction.web.model.LocalBackupValidationInfo;
import com.lifepilot.interaction.web.service.LocalBackupService;
import com.lifepilot.interaction.web.service.LocalDiagnosticService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link DiagnosticController} 单元测试。
 *
 * @author zsg
 * @since 2026-07-04
 */
class DiagnosticController_单元测试 {

    LocalDiagnosticService diagnosticService;
    LocalBackupService backupService;
    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        diagnosticService = mock(LocalDiagnosticService.class);
        backupService = mock(LocalBackupService.class);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new DiagnosticController(diagnosticService, backupService))
                .build();
    }

    @Test
    void 查询诊断报告_返回统一响应包装() throws Exception {
        when(diagnosticService.createReport()).thenReturn(new DiagnosticReportInfo(
                Instant.parse("2026-07-04T10:00:00Z"),
                "WARN",
                "本地服务可用，但有配置或运行时风险",
                Map.of("name", "zhiwei"),
                Map.of("javaVersion", "22"),
                Map.of("modelServices.enabled", 0),
                List.of(new DiagnosticCheckInfo(
                        "model-services",
                        "模型服务",
                        "WARN",
                        "没有启用的生成模型服务",
                        Map.of("generationEnabled", 0)
                )),
                List.of("模型服务：没有启用的生成模型服务")
        ));

        mockMvc.perform(get("/api/diagnostics/report"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.status").value("WARN"))
                .andExpect(jsonPath("$.data.summary").value("本地服务可用，但有配置或运行时风险"))
                .andExpect(jsonPath("$.data.checks[0].id").value("model-services"))
                .andExpect(jsonPath("$.data.hints[0]").value("模型服务：没有启用的生成模型服务"));
    }

    @Test
    void 创建诊断包_返回导出文件信息() throws Exception {
        when(diagnosticService.createDiagnosticBundle()).thenReturn(new DiagnosticBundleInfo(
                Instant.parse("2026-07-07T10:00:00Z"),
                "zhiwei-diagnostic-20260707-100000.zip",
                "D:/zhiwei/diagnostics/zhiwei-diagnostic-20260707-100000.zip",
                4096,
                3
        ));

        mockMvc.perform(post("/api/diagnostics/bundles"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.fileName").value("zhiwei-diagnostic-20260707-100000.zip"))
                .andExpect(jsonPath("$.data.path").value("D:/zhiwei/diagnostics/zhiwei-diagnostic-20260707-100000.zip"))
                .andExpect(jsonPath("$.data.sizeBytes").value(4096))
                .andExpect(jsonPath("$.data.includedFileCount").value(3));
    }

    @Test
    void 创建本地备份_返回备份文件信息() throws Exception {
        when(backupService.createBackup()).thenReturn(new LocalBackupInfo(
                Instant.parse("2026-07-04T10:00:00Z"),
                "zhiwei-backup-20260704-100000.zip",
                "D:/zhiwei/backups/zhiwei-backup-20260704-100000.zip",
                1024,
                8
        ));

        mockMvc.perform(post("/api/diagnostics/backups"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.fileName").value("zhiwei-backup-20260704-100000.zip"))
                .andExpect(jsonPath("$.data.sizeBytes").value(1024))
                .andExpect(jsonPath("$.data.includedFileCount").value(8));
    }

    @Test
    void 查询本地备份_返回最近备份文件列表() throws Exception {
        when(backupService.listBackups()).thenReturn(List.of(new LocalBackupFileInfo(
                Instant.parse("2026-07-04T11:00:00Z"),
                "zhiwei-backup-20260704-110000.zip",
                "D:/zhiwei/backups/zhiwei-backup-20260704-110000.zip",
                2048
        )));

        mockMvc.perform(get("/api/diagnostics/backups"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[0].fileName").value("zhiwei-backup-20260704-110000.zip"))
                .andExpect(jsonPath("$.data[0].modifiedAt").value("2026-07-04T11:00:00Z"))
                .andExpect(jsonPath("$.data[0].sizeBytes").value(2048));
    }

    @Test
    void 校验本地备份_返回备份结构状态() throws Exception {
        when(backupService.validateBackup("zhiwei-backup-20260704-110000.zip"))
                .thenReturn(new LocalBackupValidationInfo(
                        "zhiwei-backup-20260704-110000.zip",
                        "D:/zhiwei/backups/zhiwei-backup-20260704-110000.zip",
                        "OK",
                        "备份文件结构正常",
                        2048,
                        3,
                        true,
                        new LocalBackupManifestInfo(
                                "1",
                                Instant.parse("2026-07-04T11:00:00Z"),
                                "D:/zhiwei",
                                2,
                                List.of("backups", "cache", "logs", "runtime")
                        ),
                        List.of(),
                        new LocalBackupRestorePlanInfo(
                                "manual-staging",
                                true,
                                List.of("db", "skills"),
                                List.of("backups", "cache", "logs", "runtime"),
                                "D:/zhiwei",
                                true,
                                12,
                                "D:/old-zhiwei",
                                2,
                                "D:/zhiwei/runtime/restore-staging",
                                2048,
                                4096,
                                104857600,
                                "OK",
                                List.of("当前 HOME 已有数据，恢复前必须先关闭知微并备份当前 HOME。"),
                                List.of("关闭知微，确认没有后台进程占用 HOME 目录。")
                        )
                ));

        mockMvc.perform(post("/api/diagnostics/backups/validate")
                        .contentType("application/json")
                        .content("""
                                {"fileName":"zhiwei-backup-20260704-110000.zip"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.status").value("OK"))
                .andExpect(jsonPath("$.data.manifestPresent").value(true))
                .andExpect(jsonPath("$.data.manifest.formatVersion").value("1"))
                .andExpect(jsonPath("$.data.manifest.createdAt").value("2026-07-04T11:00:00Z"))
                .andExpect(jsonPath("$.data.restorePlan.restoreMode").value("manual-staging"))
                .andExpect(jsonPath("$.data.restorePlan.manualRestoreOnly").value(true))
                .andExpect(jsonPath("$.data.restorePlan.includedTopLevelItems[0]").value("db"))
                .andExpect(jsonPath("$.data.restorePlan.excludedTopLevelDirs[0]").value("backups"))
                .andExpect(jsonPath("$.data.restorePlan.targetHome").value("D:/zhiwei"))
                .andExpect(jsonPath("$.data.restorePlan.currentHomeHasData").value(true))
                .andExpect(jsonPath("$.data.restorePlan.restoreStagingDirectory").value("D:/zhiwei/runtime/restore-staging"))
                .andExpect(jsonPath("$.data.restorePlan.estimatedRestoreBytes").value(4096))
                .andExpect(jsonPath("$.data.restorePlan.targetUsableBytes").value(104857600))
                .andExpect(jsonPath("$.data.restorePlan.restoreSpaceStatus").value("OK"))
                .andExpect(jsonPath("$.data.restorePlan.requiredSteps[0]").value("关闭知微，确认没有后台进程占用 HOME 目录。"))
                .andExpect(jsonPath("$.data.problems").isArray());
    }

    @Test
    void 准备恢复本地备份_返回暂存目录信息() throws Exception {
        when(backupService.prepareRestore("zhiwei-backup-20260704-110000.zip"))
                .thenReturn(new LocalBackupRestorePreparationInfo(
                        Instant.parse("2026-07-07T10:00:00Z"),
                        "zhiwei-backup-20260704-110000.zip",
                        "D:/zhiwei/runtime/restore-staging/zhiwei-backup-20260704-110000-restore",
                        3,
                        4096,
                        List.of("当前 HOME 已有数据，恢复前必须先关闭知微并备份当前 HOME。"),
                        List.of("打开恢复准备目录，检查文件结构和备份清单。")
                ));

        mockMvc.perform(post("/api/diagnostics/backups/prepare-restore")
                        .contentType("application/json")
                        .content("""
                                {"fileName":"zhiwei-backup-20260704-110000.zip"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.fileName").value("zhiwei-backup-20260704-110000.zip"))
                .andExpect(jsonPath("$.data.restoreDirectory").value("D:/zhiwei/runtime/restore-staging/zhiwei-backup-20260704-110000-restore"))
                .andExpect(jsonPath("$.data.extractedFileCount").value(3))
                .andExpect(jsonPath("$.data.extractedBytes").value(4096))
                .andExpect(jsonPath("$.data.warnings[0]").value("当前 HOME 已有数据，恢复前必须先关闭知微并备份当前 HOME。"))
                .andExpect(jsonPath("$.data.nextSteps[0]").value("打开恢复准备目录，检查文件结构和备份清单。"));
    }
}
