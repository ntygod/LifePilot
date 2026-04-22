use tauri::State;

use crate::java_manager::JavaManager;
use crate::whisper_manager::WhisperManager;

/// 获取后端端口号
#[tauri::command]
pub fn get_backend_port(manager: State<JavaManager>) -> u16 {
    manager.port()
}

/// 检查后端是否存活
#[tauri::command]
pub fn is_backend_running(manager: State<JavaManager>) -> bool {
    manager.is_running()
}

/// 重启后端
#[tauri::command]
pub fn restart_backend(
    manager: State<JavaManager>,
    app: tauri::AppHandle,
) -> Result<(), String> {
    manager.restart(&app)
}

/// 获取后端日志（最近的状态信息）
#[tauri::command]
pub fn get_backend_status(manager: State<JavaManager>) -> serde_json::Value {
    serde_json::json!({
        "running": manager.is_running(),
        "port": manager.port(),
    })
}

/// 检查 Whisper CLI 和模型是否可用
#[tauri::command]
pub fn check_whisper_status(manager: State<WhisperManager>) -> serde_json::Value {
    let availability = manager.check_availability();
    serde_json::to_value(availability).unwrap_or_default()
}

/// 启动 Whisper 下载（非阻塞）
#[tauri::command]
pub fn start_whisper_download(
    manager: State<WhisperManager>,
    app: tauri::AppHandle,
) -> Result<(), String> {
    manager.start_download(app)
}

/// 取消 Whisper 下载
#[tauri::command]
pub fn cancel_whisper_download(manager: State<WhisperManager>) -> Result<(), String> {
    manager.cancel_download()
}

/// 用系统默认程序打开本地文件（如 .docx → Word，.xlsx → Excel）
/// 路径必须存在；对远程/HTTP URL 本命令不处理（前端直接用浏览器链接即可）
#[tauri::command]
pub fn open_document_path(path: String) -> Result<(), String> {
    let p = std::path::PathBuf::from(&path);
    if !p.exists() {
        return Err(format!("文件不存在: {}", path));
    }
    open_with_system_default(&path)
}

/// 在文件管理器中定位文件（Windows 资源管理器 /select、macOS Finder open -R、Linux xdg-open 父目录）
#[tauri::command]
pub fn reveal_document_in_file_manager(path: String) -> Result<(), String> {
    let p = std::path::PathBuf::from(&path);
    if !p.exists() {
        return Err(format!("文件不存在: {}", path));
    }
    reveal_in_file_manager(&path)
}

/// 系统默认程序打开 —— 跨平台实现
fn open_with_system_default(path: &str) -> Result<(), String> {
    #[cfg(target_os = "windows")]
    {
        // cmd /c start "" <path>：第一个 "" 是窗口标题占位，防止含空格的路径被当作标题
        std::process::Command::new("cmd")
            .args(["/c", "start", "", path])
            .spawn()
            .map(|_| ())
            .map_err(|e| format!("打开失败: {}", e))
    }
    #[cfg(target_os = "macos")]
    {
        std::process::Command::new("open")
            .arg(path)
            .spawn()
            .map(|_| ())
            .map_err(|e| format!("打开失败: {}", e))
    }
    #[cfg(target_os = "linux")]
    {
        std::process::Command::new("xdg-open")
            .arg(path)
            .spawn()
            .map(|_| ())
            .map_err(|e| format!("打开失败: {}", e))
    }
}

/// 在文件管理器中定位（高亮选中该文件）—— 跨平台实现
fn reveal_in_file_manager(path: &str) -> Result<(), String> {
    #[cfg(target_os = "windows")]
    {
        // explorer /select,<path> 会定位并选中（逗号后紧跟路径，无空格）
        std::process::Command::new("explorer")
            .arg(format!("/select,{}", path))
            .spawn()
            .map(|_| ())
            .map_err(|e| format!("打开文件管理器失败: {}", e))
    }
    #[cfg(target_os = "macos")]
    {
        // macOS: open -R <path> 在 Finder 里显示并选中
        std::process::Command::new("open")
            .args(["-R", path])
            .spawn()
            .map(|_| ())
            .map_err(|e| format!("打开 Finder 失败: {}", e))
    }
    #[cfg(target_os = "linux")]
    {
        // Linux 无统一的"reveal and select" API，退化为打开父目录
        let parent = std::path::Path::new(path)
            .parent()
            .ok_or_else(|| "路径没有父目录".to_string())?;
        std::process::Command::new("xdg-open")
            .arg(parent)
            .spawn()
            .map(|_| ())
            .map_err(|e| format!("打开目录失败: {}", e))
    }
}
