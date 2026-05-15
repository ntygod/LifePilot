use serde::{Deserialize, Serialize};
use std::path::{Path, PathBuf};

/// 应用级配置，存储在 Tauri app config 目录（独立于用户数据目录）。
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AppConfig {
    /// 用户数据目录，为空时使用默认 ~/zhiwei
    #[serde(default)]
    pub data_dir: Option<String>,
}

impl Default for AppConfig {
    fn default() -> Self {
        Self { data_dir: None }
    }
}

impl AppConfig {
    /// 从 config.json 加载，文件不存在或解析失败时返回默认值
    pub fn load(config_dir: &Path) -> Self {
        let path = config_dir.join("config.json");
        match std::fs::read_to_string(&path) {
            Ok(content) => serde_json::from_str(&content).unwrap_or_default(),
            Err(_) => Self::default(),
        }
    }

    /// 保存到 config.json
    pub fn save(&self, config_dir: &Path) -> Result<(), String> {
        std::fs::create_dir_all(config_dir)
            .map_err(|e| format!("创建配置目录失败: {}", e))?;
        let path = config_dir.join("config.json");
        let json = serde_json::to_string_pretty(self)
            .map_err(|e| format!("序列化配置失败: {}", e))?;
        std::fs::write(&path, json)
            .map_err(|e| format!("写入配置文件失败: {}", e))?;
        Ok(())
    }

    /// 解析实际数据目录：自定义 > 环境变量 > 默认 ~/zhiwei
    pub fn resolve_data_dir(&self) -> PathBuf {
        // 1. 应用配置中的自定义路径
        if let Some(ref custom) = self.data_dir {
            if !custom.is_empty() {
                return PathBuf::from(custom);
            }
        }
        // 2. 环境变量
        if let Ok(env_dir) = std::env::var("ZHIWEI_DATA_DIR") {
            if !env_dir.is_empty() {
                return PathBuf::from(env_dir);
            }
        }
        // 3. 默认
        dirs::home_dir()
            .map(|h| h.join("zhiwei"))
            .unwrap_or_else(|| PathBuf::from("zhiwei"))
    }
}
