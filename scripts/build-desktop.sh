#!/usr/bin/env bash
# =============================================================================
# ZhiWei 桌面端构建脚本（Unix / macOS / Linux）
#
# 用法:
#   ./scripts/build-desktop.sh          # 完整构建（JAR + JRE + 前端 + Tauri）
#   ./scripts/build-desktop.sh --skip-jar   # 跳过 JAR 构建
#   ./scripts/build-desktop.sh --skip-jre   # 跳过 JRE 构建
#   ./scripts/build-desktop.sh --dev        # 仅准备开发环境（JAR + resources）
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
TAURI_DIR="${PROJECT_ROOT}/zhiwei-web/src-tauri"
RESOURCES_DIR="${TAURI_DIR}/resources"
FRONTEND_DIR="${PROJECT_ROOT}/zhiwei-web"
MODULES_FILE="${SCRIPT_DIR}/jlink-modules.txt"

SKIP_JAR=false
SKIP_JRE=false
DEV_ONLY=false

for arg in "$@"; do
  case $arg in
    --skip-jar) SKIP_JAR=true ;;
    --skip-jre) SKIP_JRE=true ;;
    --dev) DEV_ONLY=true ;;
  esac
done

echo "======================================"
echo "  ZhiWei 桌面端构建"
echo "======================================"

# ------- 1. 环境检查 -------
echo ""
echo "[1/5] 环境检查..."

command -v java >/dev/null 2>&1 || { echo "错误: 未找到 java"; exit 1; }
command -v mvn >/dev/null 2>&1 || { echo "错误: 未找到 mvn"; exit 1; }
command -v node >/dev/null 2>&1 || { echo "错误: 未找到 node"; exit 1; }
command -v cargo >/dev/null 2>&1 || { echo "错误: 未找到 cargo"; exit 1; }

JAVA_MAJOR=$(java -version 2>&1 | head -n 1 | sed -E 's/.*"([0-9]+).*/\1/')
if [ "${JAVA_MAJOR:-0}" -lt 22 ]; then
  echo "错误: Java 版本 ${JAVA_MAJOR:-unknown}，需要 22+"
  exit 1
fi

JAVA_HOME_RESOLVED="${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(which java)")")")}"
echo "  Java ${JAVA_MAJOR} (${JAVA_HOME_RESOLVED})"
echo "  Node $(node --version)"
echo "  Cargo $(cargo --version | cut -d' ' -f2)"

# ------- 2. 构建后端 JAR -------
if [ "$SKIP_JAR" = false ]; then
  echo ""
  echo "[2/5] 构建后端 JAR..."
  cd "${PROJECT_ROOT}"
  mvn clean package -DskipTests -q
  echo "  输出: target/zhiwei.jar ($(du -h target/zhiwei.jar | cut -f1))"
else
  echo ""
  echo "[2/5] 跳过 JAR 构建"
fi

# 复制 JAR 到 resources
mkdir -p "${RESOURCES_DIR}"
cp "${PROJECT_ROOT}/target/zhiwei.jar" "${RESOURCES_DIR}/zhiwei.jar"
echo "  已复制到 ${RESOURCES_DIR}/zhiwei.jar"

# ------- 3. 构建精简 JRE -------
if [ "$SKIP_JRE" = false ]; then
  echo ""
  echo "[3/5] 构建精简 JRE (jlink)..."

  # 解析模块列表（去除注释和空行）
  MODULES=$(grep -v '^#' "${MODULES_FILE}" | grep -v '^$' | tr '\n' ',' | sed 's/,$//')

  JRE_OUTPUT="${RESOURCES_DIR}/jre"
  rm -rf "${JRE_OUTPUT}"

  "${JAVA_HOME_RESOLVED}/bin/jlink" \
    --module-path "${JAVA_HOME_RESOLVED}/jmods" \
    --add-modules "${MODULES}" \
    --no-header-files \
    --no-man-pages \
    --strip-debug \
    --compress=zip-6 \
    --output "${JRE_OUTPUT}"

  echo "  JRE 大小: $(du -sh "${JRE_OUTPUT}" | cut -f1)"
  echo "  Java 版本: $("${JRE_OUTPUT}/bin/java" --version 2>&1 | head -1)"
else
  echo ""
  echo "[3/5] 跳过 JRE 构建"
fi

# 仅开发模式到此为止
if [ "$DEV_ONLY" = true ]; then
  echo ""
  echo "开发环境准备完成！"
  echo "运行 'cd zhiwei-web && cargo tauri dev' 启动开发模式"
  exit 0
fi

# ------- 4. 构建前端 -------
echo ""
echo "[4/5] 构建前端..."
cd "${FRONTEND_DIR}"
npm install --silent
npm run build
echo "  输出: dist/ ($(du -sh dist | cut -f1))"

# ------- 5. 构建 Tauri 安装包 -------
echo ""
echo "[5/5] 构建 Tauri 安装包..."
cd "${FRONTEND_DIR}"
npx tauri build

echo ""
echo "======================================"
echo "  构建完成！"
echo "======================================"
echo ""
echo "安装包位置:"
ls -lh "${TAURI_DIR}/target/release/bundle/"*/* 2>/dev/null || echo "  (查看 ${TAURI_DIR}/target/release/bundle/)"
