use tauri::{AppHandle, Emitter, Manager, WebviewUrl, WebviewWindowBuilder};

/// 浮窗窗口标签常量
const FLOAT_WINDOW_LABEL: &str = "float";

/// 浮球尺寸（含内边距）
const BALL_SIZE: f64 = 56.0;

/// 气泡模式：气泡（180px）+ 间距（12px）+ 球（56px）= 248px
const BUBBLE_WIDTH: f64 = 340.0;
const BUBBLE_HEIGHT: f64 = 248.0;

/// 对话模式
const CHAT_WIDTH: f64 = 368.0;
const CHAT_HEIGHT: f64 = 500.0;

/// 屏幕边距
const TASKBAR_MARGIN: f64 = 80.0;
const RIGHT_MARGIN: f64 = 24.0;

/// 创建浮窗窗口。
///
/// 关键三要素：`transparent + !decorations + !shadow`（v2 必须三者同时设置）。
/// 初始隐藏，前端 mounted 后再 show，规避 WebView2 白色闪烁。
pub fn create_float_window(app: &AppHandle) -> tauri::Result<()> {
    let (screen_width, screen_height) = get_primary_screen_size(app);
    let x = screen_width - BALL_SIZE - RIGHT_MARGIN;
    let y = screen_height - BALL_SIZE - TASKBAR_MARGIN;

    let _window = WebviewWindowBuilder::new(
        app,
        FLOAT_WINDOW_LABEL,
        WebviewUrl::App("float.html".into()),
    )
    .title("")
    .inner_size(BALL_SIZE, BALL_SIZE)
    .position(x, y)
    .transparent(true)
    .decorations(false)
    .shadow(false)
    .always_on_top(true)
    .skip_taskbar(true)
    .resizable(false)
    .visible(false)
    .build()?;

    log::info!("浮窗已创建: 位置=({:.0}, {:.0}), 尺寸={:.0}x{:.0}", x, y, BALL_SIZE, BALL_SIZE);
    Ok(())
}

/// 显示浮窗
pub fn show_float_window(app: &AppHandle) -> Result<(), String> {
    get_float(app)?
        .show()
        .map_err(|e| format!("显示浮窗失败: {}", e))
}

/// Tauri command：展示提醒气泡（调整窗口到气泡尺寸 + emit 事件）
#[tauri::command]
pub fn show_reminder_bubble(
    app: AppHandle,
    notification_id: String,
    title: String,
    content: String,
    push_level: String,
) -> Result<(), String> {
    let window = get_float(&app)?;
    resize_and_reposition(&app, &window, BUBBLE_WIDTH, BUBBLE_HEIGHT)?;
    window.show().map_err(|e| format!("显示浮窗失败: {}", e))?;
    window
        .emit("reminder-bubble", serde_json::json!({
            "notificationId": notification_id,
            "title": title,
            "content": content,
            "pushLevel": push_level,
        }))
        .map_err(|e| format!("发送气泡事件失败: {}", e))?;
    log::info!("展示提醒气泡: id={}, 标题={}", notification_id, title);
    Ok(())
}

/// Tauri command：收起提醒气泡（只 emit 事件，让前端先播退出动画再 resize）
#[tauri::command]
pub fn hide_reminder_bubble(app: AppHandle) -> Result<(), String> {
    let window = get_float(&app)?;
    window.emit("reminder-dismiss", ())
        .map_err(|e| format!("发送收起事件失败: {}", e))?;
    Ok(())
}

/// Tauri command：切换浮窗模式尺寸
#[tauri::command]
pub fn resize_float_window(app: AppHandle, mode: String) -> Result<(), String> {
    let window = get_float(&app)?;
    let (w, h) = match mode.as_str() {
        "idle" => (BALL_SIZE, BALL_SIZE),
        "bubble" => (BUBBLE_WIDTH, BUBBLE_HEIGHT),
        "chat" => (CHAT_WIDTH, CHAT_HEIGHT),
        _ => return Err(format!("未知浮窗模式: {}", mode)),
    };
    resize_and_reposition(&app, &window, w, h)?;
    log::debug!("浮窗切换: mode={}, {}x{}", mode, w, h);
    Ok(())
}

// ─── 内部工具 ────────────────────────────────────────────

fn get_float(app: &AppHandle) -> Result<tauri::WebviewWindow, String> {
    app.get_webview_window(FLOAT_WINDOW_LABEL)
        .ok_or_else(|| "浮窗窗口不存在".to_string())
}

/// 调整窗口尺寸并重新定位（保持右下角锚点不变）
fn resize_and_reposition(
    app: &AppHandle,
    window: &tauri::WebviewWindow,
    width: f64,
    height: f64,
) -> Result<(), String> {
    window
        .set_size(tauri::Size::Logical(tauri::LogicalSize { width, height }))
        .map_err(|e| format!("调整尺寸失败: {}", e))?;
    let (sw, sh) = get_primary_screen_size(app);
    let x = sw - width - RIGHT_MARGIN;
    let y = sh - height - TASKBAR_MARGIN;
    window
        .set_position(tauri::Position::Logical(tauri::LogicalPosition { x, y }))
        .map_err(|e| format!("调整位置失败: {}", e))?;
    Ok(())
}

fn get_primary_screen_size(app: &AppHandle) -> (f64, f64) {
    app.primary_monitor()
        .ok()
        .flatten()
        .map(|m| {
            let s = m.size();
            let scale = m.scale_factor();
            (s.width as f64 / scale, s.height as f64 / scale)
        })
        .unwrap_or((1920.0, 1080.0))
}
