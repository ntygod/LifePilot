use std::ffi::OsString;
use std::path::{Path, PathBuf};

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
    let p = PathBuf::from(&path);
    if !p.exists() {
        return Err(format!("文件不存在: {}", path));
    }
    open_with_system_default(&path)
}

/// 在文件管理器中打开目录或定位文件。
#[tauri::command]
pub fn reveal_document_in_file_manager(path: String) -> Result<(), String> {
    let p = PathBuf::from(&path);
    if !p.exists() {
        return Err(format!("文件不存在: {}", path));
    }
    run_system_command(file_manager_command(&p)?)
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

#[derive(Debug, PartialEq, Eq)]
struct SystemCommand {
    program: &'static str,
    args: Vec<OsString>,
    error_message: &'static str,
}

fn run_system_command(command: SystemCommand) -> Result<(), String> {
    std::process::Command::new(command.program)
        .args(command.args)
        .spawn()
        .map(|_| ())
        .map_err(|e| format!("{}: {}", command.error_message, e))
}

fn file_manager_command(path: &Path) -> Result<SystemCommand, String> {
    if path.is_dir() {
        Ok(open_directory_command(path))
    } else {
        reveal_file_command(path)
    }
}

/// 打开目录 —— 跨平台实现。
fn open_directory_command(path: &Path) -> SystemCommand {
    #[cfg(target_os = "windows")]
    {
        SystemCommand {
            program: "explorer",
            args: vec![path.as_os_str().to_os_string()],
            error_message: "打开目录失败",
        }
    }
    #[cfg(target_os = "macos")]
    {
        SystemCommand {
            program: "open",
            args: vec![path.as_os_str().to_os_string()],
            error_message: "打开目录失败",
        }
    }
    #[cfg(target_os = "linux")]
    {
        SystemCommand {
            program: "xdg-open",
            args: vec![path.as_os_str().to_os_string()],
            error_message: "打开目录失败",
        }
    }
}

/// 定位文件 —— 跨平台实现。
fn reveal_file_command(path: &Path) -> Result<SystemCommand, String> {
    #[cfg(target_os = "windows")]
    {
        // explorer /select,<path> 会定位并选中（逗号后紧跟路径，无空格）。
        Ok(SystemCommand {
            program: "explorer",
            args: vec![OsString::from(format!("/select,{}", path.display()))],
            error_message: "打开文件管理器失败",
        })
    }
    #[cfg(target_os = "macos")]
    {
        // macOS: open -R <path> 在 Finder 里显示并选中。
        Ok(SystemCommand {
            program: "open",
            args: vec![OsString::from("-R"), path.as_os_str().to_os_string()],
            error_message: "打开 Finder 失败",
        })
    }
    #[cfg(target_os = "linux")]
    {
        // Linux 无统一的 reveal-and-select API，文件退化为打开父目录。
        let parent = path.parent().ok_or_else(|| "路径没有父目录".to_string())?;
        Ok(SystemCommand {
            program: "xdg-open",
            args: vec![parent.as_os_str().to_os_string()],
            error_message: "打开目录失败",
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn 文件管理器命令_目录直接打开目录() {
        let root = unique_test_path("directory");
        std::fs::create_dir_all(&root).expect("创建测试目录");

        let command = file_manager_command(&root).expect("生成目录打开命令");

        assert_eq!(command.args, vec![root.as_os_str().to_os_string()]);
        cleanup_test_path(&root);
    }

    #[cfg(target_os = "windows")]
    #[test]
    fn 文件管理器命令_windows文件使用选择模式() {
        let root = unique_test_path("file");
        std::fs::create_dir_all(&root).expect("创建测试目录");
        let file = root.join("report.txt");
        std::fs::write(&file, "report").expect("写入测试文件");

        let command = file_manager_command(&file).expect("生成文件定位命令");

        assert_eq!(command.program, "explorer");
        assert_eq!(command.args, vec![OsString::from(format!("/select,{}", file.display()))]);
        cleanup_test_path(&root);
    }

    #[cfg(target_os = "macos")]
    #[test]
    fn 文件管理器命令_macos文件使用finder定位() {
        let root = unique_test_path("file");
        std::fs::create_dir_all(&root).expect("创建测试目录");
        let file = root.join("report.txt");
        std::fs::write(&file, "report").expect("写入测试文件");

        let command = file_manager_command(&file).expect("生成文件定位命令");

        assert_eq!(command.program, "open");
        assert_eq!(command.args, vec![OsString::from("-R"), file.as_os_str().to_os_string()]);
        cleanup_test_path(&root);
    }

    #[cfg(target_os = "linux")]
    #[test]
    fn 文件管理器命令_linux文件打开父目录() {
        let root = unique_test_path("file");
        std::fs::create_dir_all(&root).expect("创建测试目录");
        let file = root.join("report.txt");
        std::fs::write(&file, "report").expect("写入测试文件");

        let command = file_manager_command(&file).expect("生成文件定位命令");

        assert_eq!(command.program, "xdg-open");
        assert_eq!(command.args, vec![root.as_os_str().to_os_string()]);
        cleanup_test_path(&root);
    }

    fn unique_test_path(label: &str) -> PathBuf {
        std::env::temp_dir().join(format!(
            "zhiwei-command-test-{}-{}",
            label,
            std::process::id()
        ))
    }

    fn cleanup_test_path(path: &Path) {
        let _ = std::fs::remove_dir_all(path);
    }
}
