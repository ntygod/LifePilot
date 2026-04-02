use tauri::State;

use crate::java_manager::JavaManager;

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
