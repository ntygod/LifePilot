use std::io::{BufRead, BufReader};
use std::path::{Path, PathBuf};
use std::process::{Child, Command, Stdio};
use std::sync::Mutex;
use std::time::Duration;

#[cfg(target_os = "windows")]
use std::os::windows::process::CommandExt;

use sysinfo::System;
use tauri::{AppHandle, Emitter};

use crate::health_check;
use crate::port_finder;

/// Java 后端进程管理器
pub struct JavaManager {
    process: Mutex<Option<Child>>,
    port: u16,
    jar_path: PathBuf,
    jre_path: Option<PathBuf>,
    #[allow(dead_code)] // Phase 2: 崩溃自动重启
    max_restart_attempts: u32,
    #[allow(dead_code)] // Phase 2: 崩溃自动重启
    restart_count: Mutex<u32>,
}

impl JavaManager {
    /// 创建新的 Java 管理器实例
    pub fn new(resource_dir: &Path) -> Self {
        let port = port_finder::find_available_port();
        let jar_path = resource_dir.join("zhiwei.jar");
        let jre_path = Self::detect_jre(resource_dir);

        log::info!(
            "JavaManager 初始化: port={}, jar={}, jre={:?}",
            port,
            jar_path.display(),
            jre_path.as_ref().map(|p| p.display().to_string())
        );

        Self {
            process: Mutex::new(None),
            port,
            jar_path,
            jre_path,
            max_restart_attempts: 2,
            restart_count: Mutex::new(0),
        }
    }

    pub fn port(&self) -> u16 {
        self.port
    }

    /// 启动 Java 后端进程
    pub fn start(&self, app: &AppHandle) -> Result<(), String> {
        let _ = app.emit("backend-stage", "resolving_java");
        let java_bin = self.resolve_java_binary()?;
        let _ = app.emit("backend-stage", "java_found");

        let xmx = Self::calculate_xmx();

        // 确保数据目录存在
        if let Some(home) = dirs::home_dir() {
            let data_dir = home.join(".zhiwei");
            std::fs::create_dir_all(&data_dir)
                .map_err(|e| format!("创建数据目录失败: {}", e))?;
        }

        log::info!(
            "启动 Java 后端: {} -Xmx{}m -jar {} --server.port={}",
            java_bin.display(),
            xmx,
            self.jar_path.display(),
            self.port
        );

        let mut cmd = Command::new(&java_bin);
        cmd.arg(format!("-Xmx{}m", xmx))
            .arg("-Xms256m")
            .arg("-XX:+UseG1GC")
            .arg("-Dfile.encoding=UTF-8")
            .arg("-Dstdout.encoding=UTF-8")
            .arg("-Dstderr.encoding=UTF-8")
            .arg("-jar")
            .arg(&self.jar_path)
            .arg(format!("--server.port={}", self.port))
            .stdout(Stdio::piped())
            .stderr(Stdio::piped());

        // Windows 下隐藏 Java 进程的控制台窗口
        #[cfg(target_os = "windows")]
        {
            const CREATE_NO_WINDOW: u32 = 0x08000000;
            cmd.creation_flags(CREATE_NO_WINDOW);
        }

        let mut child = cmd
            .spawn()
            .map_err(|e| format!("启动 Java 进程失败: {}", e))?;
        let _ = app.emit("backend-stage", "process_started");

        // 捕获 stdout 日志
        if let Some(stdout) = child.stdout.take() {
            let app_clone = app.clone();
            std::thread::spawn(move || {
                let reader = BufReader::new(stdout);
                for line in reader.lines() {
                    if let Ok(line) = line {
                        log::info!("[Java] {}", line);
                        let _ = app_clone.emit("backend-log", &line);
                    }
                }
            });
        }

        // 捕获 stderr 日志
        if let Some(stderr) = child.stderr.take() {
            let app_clone = app.clone();
            std::thread::spawn(move || {
                let reader = BufReader::new(stderr);
                for line in reader.lines() {
                    if let Ok(line) = line {
                        log::warn!("[Java:err] {}", line);
                        let _ = app_clone.emit("backend-log", &line);
                    }
                }
            });
        }

        *self.process.lock().expect("JavaManager process mutex 中毒") = Some(child);

        // 异步等待健康检查 + 监控进程
        let _ = app.emit("backend-stage", "health_check");
        let port = self.port;
        let app_handle = app.clone();
        tauri::async_runtime::spawn(async move {
            match health_check::wait_for_backend(port, Duration::from_secs(60)).await {
                Ok(()) => {
                    log::info!("后端已就绪，发送 backend-ready 事件");
                    let _ = app_handle.emit("backend-ready", port);
                }
                Err(e) => {
                    log::error!("后端启动失败: {}", e);
                    let _ = app_handle.emit("backend-error", e);
                }
            }
        });

        Ok(())
    }

    /// 优雅关闭 Java 后端
    pub fn stop(&self) -> Result<(), String> {
        if let Some(mut child) = self.process.lock().expect("JavaManager process mutex 中毒").take() {
            log::info!("正在关闭 Java 后端进程...");

            // Windows 下使用 taskkill /F 强制终止进程树（Java 控制台进程无窗口句柄，不带 /F 会被忽略）
            #[cfg(target_os = "windows")]
            {
                const CREATE_NO_WINDOW: u32 = 0x08000000;
                let pid = child.id();
                let _ = Command::new("taskkill")
                    .args(["/F", "/PID", &pid.to_string(), "/T"])
                    .creation_flags(CREATE_NO_WINDOW)
                    .output();
            }

            // Unix 下发送 SIGTERM
            #[cfg(not(target_os = "windows"))]
            {
                unsafe {
                    libc::kill(child.id() as i32, libc::SIGTERM);
                }
            }

            // 等待最多 10 秒
            let deadline = std::time::Instant::now() + Duration::from_secs(10);
            loop {
                match child.try_wait() {
                    Ok(Some(status)) => {
                        log::info!("Java 进程已退出: {}", status);
                        return Ok(());
                    }
                    Ok(None) => {
                        if std::time::Instant::now() > deadline {
                            log::warn!("等待超时，强制终止 Java 进程");
                            let _ = child.kill();
                            let _ = child.wait();
                            return Ok(());
                        }
                        std::thread::sleep(Duration::from_millis(200));
                    }
                    Err(e) => {
                        return Err(format!("等待进程退出失败: {}", e));
                    }
                }
            }
        }
        Ok(())
    }

    /// 重启 Java 后端
    pub fn restart(&self, app: &AppHandle) -> Result<(), String> {
        self.stop()?;
        std::thread::sleep(Duration::from_secs(1));
        self.start(app)
    }

    /// 检查 Java 进程是否存活
    pub fn is_running(&self) -> bool {
        if let Some(ref mut child) = *self.process.lock().expect("JavaManager process mutex 中毒") {
            child.try_wait().map(|opt| opt.is_none()).unwrap_or(false)
        } else {
            false
        }
    }

    /// 检测内嵌 JRE 路径
    fn detect_jre(resource_dir: &Path) -> Option<PathBuf> {
        let jre_dir = resource_dir.join("jre");
        if jre_dir.exists() {
            Some(jre_dir)
        } else {
            None
        }
    }

    /// 解析 Java 可执行文件路径
    fn resolve_java_binary(&self) -> Result<PathBuf, String> {
        // 优先使用内嵌 JRE
        if let Some(ref jre_path) = self.jre_path {
            let java_bin = if cfg!(target_os = "windows") {
                jre_path.join("bin").join("java.exe")
            } else {
                jre_path.join("bin").join("java")
            };
            if java_bin.exists() {
                log::info!("使用内嵌 JRE: {}", java_bin.display());
                return Ok(java_bin);
            }
        }

        // 回退到系统 Java
        let java_cmd = if cfg!(target_os = "windows") {
            "java.exe"
        } else {
            "java"
        };

        // 检查 JAVA_HOME
        if let Ok(java_home) = std::env::var("JAVA_HOME") {
            let java_bin = PathBuf::from(&java_home)
                .join("bin")
                .join(java_cmd);
            if java_bin.exists() {
                log::info!("使用 JAVA_HOME: {}", java_bin.display());
                return Ok(java_bin);
            }
        }

        // 检查 PATH
        log::info!("使用系统 PATH 中的 java");
        Ok(PathBuf::from(java_cmd))
    }

    /// 计算 JVM 最大堆内存（系统内存 50%，上限 2048MB，下限 512MB）
    fn calculate_xmx() -> u64 {
        let mut sys = System::new();
        sys.refresh_memory();
        let total_mb = sys.total_memory() / 1024 / 1024;
        let xmx = total_mb / 2;
        xmx.clamp(512, 2048)
    }
}

impl Drop for JavaManager {
    fn drop(&mut self) {
        if let Err(e) = self.stop() {
            log::error!("JavaManager Drop 关闭进程失败: {}", e);
        }
    }
}
