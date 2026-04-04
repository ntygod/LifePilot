mod commands;
mod health_check;
mod java_manager;
mod port_finder;
mod tray;
mod whisper_manager;

use java_manager::JavaManager;
use whisper_manager::WhisperManager;
use std::path::PathBuf;
use tauri::Manager;
use tauri_plugin_autostart::MacosLauncher;

/// Tauri 插件注册入口
#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_single_instance::init(|app, _args, _cwd| {
            // 重复启动时聚焦已有窗口
            if let Some(window) = app.get_webview_window("main") {
                let _ = window.show();
                let _ = window.unminimize();
                let _ = window.set_focus();
            }
        }))
        // MacosLauncher 参数仅在 macOS 生效，Windows/Linux 上被忽略
        .plugin(tauri_plugin_autostart::init(MacosLauncher::LaunchAgent, None))
        .plugin(tauri_plugin_shell::init())
        .plugin(tauri_plugin_process::init())
        .plugin(tauri_plugin_notification::init())
        .setup(|app| {
            // 解析资源目录（开发环境和生产环境路径不同）
            let resource_dir = resolve_resource_dir(app);
            log::info!("资源目录: {}", resource_dir.display());

            // 初始化 Whisper 管理器（计算目标路径，供 Java 后端使用）
            let whisper_manager = WhisperManager::new();

            // 初始化 Java 管理器（传入 Whisper 路径）
            let java_manager = JavaManager::new(
                &resource_dir,
                Some(whisper_manager.cli_path()),
                Some(whisper_manager.model_path()),
            );

            // 启动 Java 后端
            let app_handle = app.handle().clone();
            java_manager.start(&app_handle)?;

            // 注册到全局状态
            app.manage(java_manager);
            app.manage(whisper_manager);

            // 初始化系统托盘
            tray::setup_tray(app.handle())?;

            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            commands::get_backend_port,
            commands::is_backend_running,
            commands::restart_backend,
            commands::get_backend_status,
            commands::check_whisper_status,
            commands::start_whisper_download,
            commands::cancel_whisper_download,
        ])
        .on_window_event(|window, event| {
            if let tauri::WindowEvent::CloseRequested { api, .. } = event {
                // 关闭窗口时隐藏到托盘，而不是退出
                let _ = window.hide();
                api.prevent_close();
            }
        })
        .run(tauri::generate_context!())
        .expect("ZhiWei 桌面端启动失败");
}

/// 解析资源目录路径
fn resolve_resource_dir(app: &tauri::App) -> PathBuf {
    // 生产环境：Tauri 打包的 resources 目录
    if let Ok(resource_path) = app.path().resource_dir() {
        let bundled = resource_path.join("resources");
        if bundled.exists() {
            return strip_extended_path_prefix(bundled);
        }
    }

    // 开发环境：src-tauri/resources 或项目根目录的 target
    let dev_resources = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("resources");
    if dev_resources.exists() {
        return dev_resources;
    }

    // 最终回退：项目根目录的 target
    let manifest = PathBuf::from(env!("CARGO_MANIFEST_DIR"));
    manifest
        .parent()
        .and_then(|p| p.parent())
        .map(|p| p.join("target"))
        .unwrap_or(manifest)
}

/// 去除 Windows 扩展路径前缀 `\\?\`，Java 无法识别该格式
fn strip_extended_path_prefix(path: PathBuf) -> PathBuf {
    let s = path.to_string_lossy();
    if let Some(stripped) = s.strip_prefix(r"\\?\") {
        PathBuf::from(stripped)
    } else {
        path
    }
}
