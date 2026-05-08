#!/usr/bin/env bash
#
# 下载 Memory Eval Harness 使用的开源基准数据集。
#
# 支持的数据集：
#   - LoCoMo (snap-research/locomo, Apache-2.0)
#   - LongMemEval (xiaowu0162/LongMemEval, MIT, ICLR 2025)
#
# 用法：
#   ./scripts/download-eval-datasets.sh            # 首次下载，已存在则跳过
#   ./scripts/download-eval-datasets.sh --force    # 强制重新下载
#
# 默认缓存目录：${LIFEPILOT_EVAL_CACHE:-$HOME/.zhiwei/eval-cache}
#
# @author zsg
# @since 2026-05-09

set -euo pipefail

CACHE_DIR="${LIFEPILOT_EVAL_CACHE:-$HOME/.zhiwei/eval-cache}"
FORCE=0

for arg in "$@"; do
  case "$arg" in
    --force) FORCE=1 ;;
    -h|--help)
      cat <<EOF
用法: $0 [--force]

选项：
  --force    强制重新下载，覆盖已有数据

环境变量：
  LIFEPILOT_EVAL_CACHE   自定义缓存目录（默认 ~/.zhiwei/eval-cache）

下载的数据集：
  - LoCoMo                 → \$CACHE_DIR/locomo
  - LongMemEval            → \$CACHE_DIR/longmemeval
EOF
      exit 0
      ;;
    *)
      echo "未识别的参数: $arg" >&2
      exit 2
      ;;
  esac
done

if ! command -v git >/dev/null 2>&1; then
  echo "错误: 需要 git 命令。请先安装 git。" >&2
  exit 1
fi

mkdir -p "$CACHE_DIR"
echo "缓存目录: $CACHE_DIR"

download_git_repo() {
  local name="$1"
  local repo_url="$2"
  local dest="$CACHE_DIR/$name"
  local check_file="$3"

  if [[ -d "$dest" ]] && [[ $FORCE -eq 0 ]]; then
    echo "[$name] 已存在，跳过。使用 --force 强制重新下载。"
    return
  fi

  if [[ -d "$dest" ]] && [[ $FORCE -eq 1 ]]; then
    echo "[$name] 清理已有目录..."
    rm -rf "$dest"
  fi

  echo "[$name] 克隆 $repo_url ..."
  if ! git clone --depth 1 "$repo_url" "$dest"; then
    echo "[$name] 克隆失败。网络不通？" >&2
    exit 3
  fi

  if [[ -n "$check_file" ]]; then
    if [[ ! -f "$dest/$check_file" ]]; then
      echo "[$name] 警告：关键文件 $check_file 未找到。数据集结构可能与预期不同。" >&2
    else
      echo "[$name] 校验通过：$check_file 存在"
    fi
  fi
}

download_git_repo "locomo" \
  "https://github.com/snap-research/locomo.git" \
  "data/locomo10.json"

download_git_repo "longmemeval" \
  "https://github.com/xiaowu0162/LongMemEval.git" \
  "data/longmemeval_s.json"

# LongMemEval 的数据文件可能在 data/ 目录，兼容两种路径
if [[ -f "$CACHE_DIR/longmemeval/data/longmemeval_s.json" ]] \
    && [[ ! -f "$CACHE_DIR/longmemeval/longmemeval_s.json" ]]; then
  echo "[longmemeval] 建立符号链接以便 Loader 默认路径命中..."
  ln -sf "data/longmemeval_s.json" "$CACHE_DIR/longmemeval/longmemeval_s.json" \
    || cp "$CACHE_DIR/longmemeval/data/longmemeval_s.json" \
         "$CACHE_DIR/longmemeval/longmemeval_s.json"
fi

echo ""
echo "所有数据集下载完成。运行 'mvn test -Pmemory-eval-quick' 开始首次评估。"
