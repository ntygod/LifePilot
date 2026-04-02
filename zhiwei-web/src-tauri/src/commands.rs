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

/// 检查 Whisper 语音引擎可用状态
#[tauri::command]
pub fn check_whisper_status(
    whisper: State<WhisperManager>,
) -> Result<serde_json::Value, String> {
    let availability = whisper.check_availability();
    serde_json::to_value(availability).map_err(|e| format!("序列化失败: {}", e))
}

/// 启动 Whisper 语音引擎下载
#[tauri::command]
pub fn start_whisper_download(
    whisper: State<WhisperManager>,
    app: tauri::AppHandle,
) -> Result<(), String> {
    whisper.start_download(app)
}

/// 取消 Whisper 语音引擎下载
#[tauri::command]
pub fn cancel_whisper_download(
    whisper: State<WhisperManager>,
) -> Result<(), String> {
    whisper.cancel_download()
}
