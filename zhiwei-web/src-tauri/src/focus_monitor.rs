use std::time::Duration;

use tauri::AppHandle;

/// 焦点状态上报间隔（秒）
const REPORT_INTERVAL_SECS: u64 = 10;

/// 启动焦点监控 — 定期采样前台窗口信息并上报给后端。
pub fn start_focus_monitor(_app: AppHandle, backend_port: u16) {
    std::thread::Builder::new()
        .name("focus-monitor".to_string())
        .spawn(move || {
            log::info!("焦点监控启动: interval={}s, port={}", REPORT_INTERVAL_SECS, backend_port);
            loop {
                std::thread::sleep(Duration::from_secs(REPORT_INTERVAL_SECS));
                if let Some(state) = collect_focus_state() {
                    report_to_backend(backend_port, &state);
                }
            }
        })
        .expect("焦点监控线程创建失败");
}

/// 采集当前焦点窗口状态
fn collect_focus_state() -> Option<FocusState> {
    #[cfg(target_os = "windows")]
    {
        collect_focus_state_windows()
    }
    #[cfg(not(target_os = "windows"))]
    {
        // macOS/Linux 暂不实现
        None
    }
}

#[cfg(target_os = "windows")]
fn collect_focus_state_windows() -> Option<FocusState> {
    use windows::Win32::UI::WindowsAndMessaging::{
        GetForegroundWindow, GetWindowTextW, GetWindowThreadProcessId, IsZoomed,
    };
    use windows::Win32::UI::Input::KeyboardAndMouse::{GetLastInputInfo, LASTINPUTINFO};
    use windows::Win32::System::SystemInformation::GetTickCount;

    unsafe {
        let hwnd = GetForegroundWindow();
        if hwnd.0 == std::ptr::null_mut() {
            return None;
        }

        // 获取窗口标题
        let mut title_buf = [0u16; 512];
        let title_len = GetWindowTextW(hwnd, &mut title_buf);
        let title = String::from_utf16_lossy(&title_buf[..title_len as usize]);

        // 获取进程名
        let mut pid = 0u32;
        GetWindowThreadProcessId(hwnd, Some(&mut pid));
        let app_name = get_process_name(pid).unwrap_or_default();

        // 判断是否全屏（最大化）
        let fullscreen = IsZoomed(hwnd).as_bool();

        // 计算空闲时间
        let mut last_input = LASTINPUTINFO {
            cbSize: std::mem::size_of::<LASTINPUTINFO>() as u32,
            dwTime: 0,
        };
        let idle_minutes = if GetLastInputInfo(&mut last_input).as_bool() {
            let tick = GetTickCount();
            let idle_ms = tick.wrapping_sub(last_input.dwTime);
            (idle_ms / 60000) as i32
        } else {
            0
        };

        Some(FocusState {
            focus_app: app_name,
            focus_title: title,
            fullscreen,
            idle_minutes,
        })
    }
}

#[cfg(target_os = "windows")]
unsafe fn get_process_name(pid: u32) -> Option<String> {
    use windows::Win32::System::Threading::{
        OpenProcess, QueryFullProcessImageNameW, PROCESS_NAME_FORMAT,
        PROCESS_QUERY_LIMITED_INFORMATION,
    };
    use windows::Win32::Foundation::CloseHandle;
    use windows::core::PWSTR;

    let handle = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, false, pid).ok()?;
    let mut buf = [0u16; 260];
    let mut size = buf.len() as u32;
    let pwstr = PWSTR::from_raw(buf.as_mut_ptr());
    let ok = QueryFullProcessImageNameW(handle, PROCESS_NAME_FORMAT(0), pwstr, &mut size).is_ok();
    let _ = CloseHandle(handle);
    if ok && size > 0 {
        let path = String::from_utf16_lossy(&buf[..size as usize]);
        path.rsplit('\\').next().map(|s| s.to_string())
    } else {
        None
    }
}

/// 上报焦点状态到后端
fn report_to_backend(port: u16, state: &FocusState) {
    let url = format!("http://127.0.0.1:{}/api/context/focus", port);
    let body = serde_json::json!({
        "focusApp": state.focus_app,
        "focusTitle": state.focus_title,
        "fullscreen": state.fullscreen,
        "idleMinutes": state.idle_minutes,
    });

    match reqwest::blocking::Client::new()
        .post(&url)
        .json(&body)
        .timeout(Duration::from_secs(3))
        .send()
    {
        Ok(_) => log::debug!(
            "焦点状态上报成功: app={}, title={}, fullscreen={}, idle={}min",
            state.focus_app, state.focus_title, state.fullscreen, state.idle_minutes
        ),
        Err(e) => log::debug!("焦点状态上报失败: {}", e),
    }
}

struct FocusState {
    focus_app: String,
    focus_title: String,
    fullscreen: bool,
    idle_minutes: i32,
}
