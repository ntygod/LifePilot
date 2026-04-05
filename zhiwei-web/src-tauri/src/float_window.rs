use tauri::{AppHandle, Emitter, Manager, WebviewUrl, WebviewWindowBuilder};

/// 浮窗窗口标签常量
const FLOAT_WINDOW_LABEL: &str = "float";

/// 浮窗默认尺寸（小圆点，含阴影 padding）
const IDLE_WIDTH: f64 = 64.0;
const IDLE_HEIGHT: f64 = 64.0;

/// 气泡 / 快捷面板展开尺寸
const BUBBLE_WIDTH: f64 = 316.0;
const BUBBLE_HEIGHT: f64 = 220.0;

/// 迷你对话窗口尺寸
const CHAT_WIDTH: f64 = 376.0;
const CHAT_HEIGHT: f64 = 496.0;

/// 任务栏预留高度（像素）
const TASKBAR_MARGIN: f64 = 80.0;
/// 屏幕右侧预留边距
const RIGHT_MARGIN: f64 = 24.0;

/// 创建浮窗窗口（frameless + transparent + always_on_top）
///
/// 窗口初始状态为 64x64 的小圆点，定位在屏幕右下角。
/// 使用独立的 /float.html 入口，与主窗口隔离。
pub fn create_float_window(app: &AppHandle) -> tauri::Result<()> {
    // 获取主显示器分辨率，用于计算右下角位置
    let (screen_width, screen_height) = get_primary_screen_size(app);
    let x = screen_width - IDLE_WIDTH - RIGHT_MARGIN;
    let y = screen_height - IDLE_HEIGHT - TASKBAR_MARGIN;

    let float_window = WebviewWindowBuilder::new(
        app,
        FLOAT_WINDOW_LABEL,
        WebviewUrl::App("float.html".into()),
    )
    .title("知微助手")
    .inner_size(IDLE_WIDTH, IDLE_HEIGHT)
    .position(x, y)
    .transparent(true)
    .decorations(false)
    .always_on_top(true)
    .skip_taskbar(true)
    .resizable(false)
    .visible(false) // 初始隐藏，等后端就绪后再显示
    .build()?;

    log::info!(
        "浮窗已创建: 位置=({}, {}), 尺寸={}x{}",
        x, y, IDLE_WIDTH, IDLE_HEIGHT
    );

    // 忽略 unused 警告 — 窗口句柄由 Tauri 全局管理
    let _ = float_window;

    Ok(())
}

/// 显示浮窗
pub fn show_float_window(app: &AppHandle) -> Result<(), String> {
    let window = app
        .get_webview_window(FLOAT_WINDOW_LABEL)
        .ok_or("浮窗窗口不存在")?;
    window.show().map_err(|e| format!("显示浮窗失败: {}", e))?;
    Ok(())
}

/// 隐藏浮窗
#[allow(dead_code)]
pub fn hide_float_window(app: &AppHandle) -> Result<(), String> {
    let window = app
        .get_webview_window(FLOAT_WINDOW_LABEL)
        .ok_or("浮窗窗口不存在")?;
    window.hide().map_err(|e| format!("隐藏浮窗失败: {}", e))?;
    Ok(())
}

/// Tauri command：展示提醒气泡
///
/// 前端调用此命令后，浮窗从 idle 尺寸切换到 bubble 尺寸，
/// 并通过事件通知前端渲染气泡内容。
#[tauri::command]
pub fn show_reminder_bubble(
    app: AppHandle,
    notification_id: String,
    title: String,
    content: String,
    push_level: String,
) -> Result<(), String> {
    let window = app
        .get_webview_window(FLOAT_WINDOW_LABEL)
        .ok_or("浮窗窗口不存在")?;

    // 切换到气泡尺寸
    window
        .set_size(tauri::Size::Logical(tauri::LogicalSize {
            width: BUBBLE_WIDTH,
            height: BUBBLE_HEIGHT,
        }))
        .map_err(|e| format!("调整浮窗尺寸失败: {}", e))?;

    // 重新定位到右下角（考虑新尺寸）
    reposition_to_bottom_right(&app, &window, BUBBLE_WIDTH, BUBBLE_HEIGHT)?;

    // 显示窗口
    window.show().map_err(|e| format!("显示浮窗失败: {}", e))?;

    // 通过事件通知前端渲染气泡
    window
        .emit(
            "reminder-bubble",
            serde_json::json!({
                "notificationId": notification_id,
                "title": title,
                "content": content,
                "pushLevel": push_level,
            }),
        )
        .map_err(|e| format!("发送气泡事件失败: {}", e))?;

    log::info!("展示提醒气泡: id={}, 标题={}", notification_id, title);
    Ok(())
}

/// Tauri command：收起提醒气泡，恢复到 idle 小圆点
#[tauri::command]
pub fn hide_reminder_bubble(app: AppHandle) -> Result<(), String> {
    let window = app
        .get_webview_window(FLOAT_WINDOW_LABEL)
        .ok_or("浮窗窗口不存在")?;

    // 恢复到 idle 尺寸
    window
        .set_size(tauri::Size::Logical(tauri::LogicalSize {
            width: IDLE_WIDTH,
            height: IDLE_HEIGHT,
        }))
        .map_err(|e| format!("恢复浮窗尺寸失败: {}", e))?;

    // 重新定位
    reposition_to_bottom_right(&app, &window, IDLE_WIDTH, IDLE_HEIGHT)?;

    // 通知前端切回 idle 状态
    window
        .emit("reminder-dismiss", ())
        .map_err(|e| format!("发送收起事件失败: {}", e))?;

    log::info!("收起提醒气泡");
    Ok(())
}

/// Tauri command：在不同状态间切换浮窗大小
///
/// mode 取值: "idle" | "glow" | "bubble" | "chat"
#[tauri::command]
pub fn resize_float_window(app: AppHandle, mode: String) -> Result<(), String> {
    let window = app
        .get_webview_window(FLOAT_WINDOW_LABEL)
        .ok_or("浮窗窗口不存在")?;

    let (width, height) = match mode.as_str() {
        "idle" | "glow" => (IDLE_WIDTH, IDLE_HEIGHT),
        "bubble" => (BUBBLE_WIDTH, BUBBLE_HEIGHT),
        "chat" => (CHAT_WIDTH, CHAT_HEIGHT),
        _ => return Err(format!("未知的浮窗模式: {}", mode)),
    };

    window
        .set_size(tauri::Size::Logical(tauri::LogicalSize { width, height }))
        .map_err(|e| format!("调整浮窗尺寸失败: {}", e))?;

    reposition_to_bottom_right(&app, &window, width, height)?;

    log::debug!("浮窗切换到 {} 模式: {}x{}", mode, width, height);
    Ok(())
}

/// 将窗口重新定位到屏幕右下角
fn reposition_to_bottom_right(
    app: &AppHandle,
    window: &tauri::WebviewWindow,
    width: f64,
    height: f64,
) -> Result<(), String> {
    let (screen_width, screen_height) = get_primary_screen_size(app);
    let x = screen_width - width - RIGHT_MARGIN;
    let y = screen_height - height - TASKBAR_MARGIN;

    window
        .set_position(tauri::Position::Logical(tauri::LogicalPosition { x, y }))
        .map_err(|e| format!("调整浮窗位置失败: {}", e))?;

    Ok(())
}

/// 获取主显示器逻辑尺寸
fn get_primary_screen_size(app: &AppHandle) -> (f64, f64) {
    if let Some(monitor) = app.primary_monitor().ok().flatten() {
        let size = monitor.size();
        let scale = monitor.scale_factor();
        (
            size.width as f64 / scale,
            size.height as f64 / scale,
        )
    } else {
        // 回退默认值（1920x1080）
        log::warn!("无法获取主显示器信息，使用默认分辨率 1920x1080");
        (1920.0, 1080.0)
    }
}
