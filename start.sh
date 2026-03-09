#!/usr/bin/env bash
set -euo pipefail

VERSION="0.2.0"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")" && pwd)"
JAR_FILE="${SCRIPT_DIR}/zhiwei.jar"

# 1. 检测 java 命令
if ! command -v java &>/dev/null; then
    echo "错误: 未找到 java 命令"
    echo "请安装 Java 22 或更高版本: https://adoptium.net/"
    exit 1
fi

# 2. 检测 Java 版本 >= 22
JAVA_VERSION_RAW=$(java -version 2>&1 | head -n 1)
JAVA_MAJOR=$(echo "$JAVA_VERSION_RAW" | sed -E 's/.*"([0-9]+).*/\1/')
if [ -z "$JAVA_MAJOR" ] || [ "$JAVA_MAJOR" -lt 22 ]; then
    echo "错误: 当前 Java 版本为 ${JAVA_MAJOR:-unknown}，要求 22 或更高"
    echo "请下载 Java 22+: https://adoptium.net/"
    exit 1
fi

# 3. 检查 JAR 文件
if [ ! -f "$JAR_FILE" ]; then
    echo "错误: 未找到 ${JAR_FILE}"
    echo "请先构建项目: mvn clean package -DskipTests"
    exit 1
fi

# 4. 创建数据目录
DATA_DIR="${HOME}/.zhiwei"
mkdir -p "${DATA_DIR}"

# 5. 计算 JVM 内存参数（系统内存 50%，上限 2048MB，检测失败回退 512MB）
XMX=512
if command -v sysctl &>/dev/null && sysctl hw.memsize &>/dev/null 2>&1; then
    TOTAL_MEM_MB=$(( $(sysctl -n hw.memsize) / 1024 / 1024 ))
elif [ -r /proc/meminfo ]; then
    TOTAL_MEM_KB=$(grep MemTotal /proc/meminfo | awk '{print $2}')
    TOTAL_MEM_MB=$(( TOTAL_MEM_KB / 1024 ))
else
    TOTAL_MEM_MB=0
fi

if [ "${TOTAL_MEM_MB:-0}" -gt 0 ]; then
    XMX=$(( TOTAL_MEM_MB / 2 ))
    [ "$XMX" -gt 2048 ] && XMX=2048
fi

# 6. 读取自定义端口
PORT="${ZHIWEI_PORT:-8080}"

# 7. 输出启动信息
echo "╔══════════════════════════════════════╗"
echo "║  ZhiWei（知微）v${VERSION}               ║"
echo "║  见微知著，你的 AI 伙伴             ║"
echo "╠══════════════════════════════════════╣"
echo "║  访问地址: http://localhost:${PORT}     ║"
echo "║  数据目录: ~/.zhiwei/                ║"
echo "║  JVM 内存: ${XMX}m                     ║"
echo "╚══════════════════════════════════════╝"

# 8. 启动应用
exec java -Xmx${XMX}m -jar "${JAR_FILE}" --server.port="${PORT}"
