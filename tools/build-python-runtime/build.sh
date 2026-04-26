#!/usr/bin/env bash
# 构建 Linux/macOS 平台的知微 Python runtime tarball
# 用法: build.sh <python-version> <platform> <arch>
# 示例: build.sh 3.12.13 linux x86_64
set -euo pipefail

VERSION="${1:?usage: build.sh <python-version> <platform> <arch>}"
PLATFORM="${2:?usage: build.sh <python-version> <platform> <arch>}"
ARCH="${3:?usage: build.sh <python-version> <platform> <arch>}"
OUT_DIR="dist"

mkdir -p "$OUT_DIR"

# python-build-standalone 发布 tag（每月滚动更新，以构建时点为准）
# 来源: https://github.com/astral-sh/python-build-standalone/releases
PBS_TAG="${PBS_TAG:-20260414}"

# python-build-standalone tarball 命名约定：
# cpython-{version}+{tag}-{triple}-install_only.tar.gz
case "$PLATFORM-$ARCH" in
  linux-x86_64)   PBS_TRIPLE="x86_64-unknown-linux-gnu" ;;
  linux-arm64)    PBS_TRIPLE="aarch64-unknown-linux-gnu" ;;
  macos-x86_64)   PBS_TRIPLE="x86_64-apple-darwin" ;;
  macos-arm64)    PBS_TRIPLE="aarch64-apple-darwin" ;;
  *) echo "不支持的平台组合: $PLATFORM-$ARCH" >&2; exit 1 ;;
esac

PBS_URL="https://github.com/astral-sh/python-build-standalone/releases/download/${PBS_TAG}/cpython-${VERSION}+${PBS_TAG}-${PBS_TRIPLE}-install_only.tar.gz"

echo "下载 python-build-standalone: $PBS_URL"
curl -fL -o pbs.tar.gz "$PBS_URL"

mkdir -p extract
tar -xzf pbs.tar.gz -C extract
PYTHON_DIR="extract/python"

if [[ ! -x "$PYTHON_DIR/bin/python3" ]]; then
    echo "解压后未找到 $PYTHON_DIR/bin/python3" >&2
    exit 1
fi

echo "安装预装库 (requirements.txt)"
"$PYTHON_DIR/bin/python3" -m pip install --no-cache-dir --upgrade pip
"$PYTHON_DIR/bin/python3" -m pip install --no-cache-dir -r tools/build-python-runtime/requirements.txt

# 写 VERSION 文件，运行时校验用
echo "$VERSION" > "$PYTHON_DIR/VERSION"

# 打 tar.zst（依赖 zstd 命令）
TARBALL="$OUT_DIR/zhiwei-python-runtime-${VERSION}-${PLATFORM}-${ARCH}.tar.zst"
echo "压缩为 $TARBALL"
tar -cf - -C extract python | zstd -19 -o "$TARBALL"

# 算 SHA-256（Linux 用 sha256sum，macOS 用 shasum）
if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$TARBALL" | awk '{print $1}' > "${TARBALL}.sha256"
else
    shasum -a 256 "$TARBALL" | awk '{print $1}' > "${TARBALL}.sha256"
fi

echo "已生成 $TARBALL"
ls -lh "$TARBALL" "${TARBALL}.sha256"
