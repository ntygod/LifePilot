package com.lifepilot.interaction.web.controller;

import com.lifepilot.interaction.web.model.ApiResponse;
import com.lifepilot.interaction.web.model.DiagnosticBundleInfo;
import com.lifepilot.interaction.web.model.DiagnosticReportInfo;
import com.lifepilot.interaction.web.model.LocalBackupFileInfo;
import com.lifepilot.interaction.web.model.LocalBackupInfo;
import com.lifepilot.interaction.web.model.LocalBackupRestorePreparationInfo;
import com.lifepilot.interaction.web.model.LocalBackupValidationInfo;
import com.lifepilot.interaction.web.model.LocalBackupValidationRequest;
import com.lifepilot.interaction.web.service.LocalBackupService;
import com.lifepilot.interaction.web.service.LocalDiagnosticService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.List;

/**
 * 本地诊断报告 REST Controller。
 *
 * <p>面向桌面端排障场景，提供轻量只读报告，便于主对话错误复制时附带系统状态。</p>
 *
 * @author zsg
 * @since 2026-07-04
 */
@RestController
@RequestMapping("/api/diagnostics")
@ConditionalOnProperty(name = "lifepilot.gateway.channels.web.enabled", havingValue = "true")
public class DiagnosticController {

    private final LocalDiagnosticService diagnosticService;
    private final LocalBackupService backupService;

    public DiagnosticController(LocalDiagnosticService diagnosticService, LocalBackupService backupService) {
        this.diagnosticService = diagnosticService;
        this.backupService = backupService;
    }

    @GetMapping("/report")
    public ApiResponse<DiagnosticReportInfo> report() {
        return ApiResponse.ok(diagnosticService.createReport());
    }

    @PostMapping("/bundles")
    public ApiResponse<DiagnosticBundleInfo> createDiagnosticBundle() throws IOException {
        return ApiResponse.ok(diagnosticService.createDiagnosticBundle());
    }

    @PostMapping("/backups")
    public ApiResponse<LocalBackupInfo> createBackup() throws IOException {
        return ApiResponse.ok(backupService.createBackup());
    }

    @GetMapping("/backups")
    public ApiResponse<List<LocalBackupFileInfo>> listBackups() throws IOException {
        return ApiResponse.ok(backupService.listBackups());
    }

    @PostMapping("/backups/validate")
    public ApiResponse<LocalBackupValidationInfo> validateBackup(@RequestBody LocalBackupValidationRequest request)
            throws IOException {
        return ApiResponse.ok(backupService.validateBackup(request.fileName()));
    }

    @PostMapping("/backups/prepare-restore")
    public ApiResponse<LocalBackupRestorePreparationInfo> prepareBackupRestore(
            @RequestBody LocalBackupValidationRequest request) throws IOException {
        return ApiResponse.ok(backupService.prepareRestore(request.fileName()));
    }
}
