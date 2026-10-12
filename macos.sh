#!/bin/bash
# Build and package the current checkout for Apple Silicon and Intel Macs.
set -euo pipefail

usage() {
    cat <<'EOF'
Usage: ./macos.sh

Build arm64 and x86_64, combine them into a universal app, and create a ZIP.
Tests and FFmpeg video recording are disabled. The app uses ad-hoc signing.

Environment variables:
  QT_ROOT       Universal Qt 6 installation, including Multimedia and LinguistTools.
                Default: build/arm64/externals/qt/6.10.3/macos in this repository.
  BUILD_ROOT    Parent directory for build runs.
                Default: build/macos-universal in this repository.
  JOBS          Parallel build jobs (default: 8).
  SKIP_SUBMODULE_UPDATE
                Set to 1 to use the existing submodule checkouts (default: 0).

Examples:
  ./macos.sh
  JOBS=4 ./macos.sh
  QT_ROOT=/path/to/Qt/6.10.3/macos ./macos.sh
EOF
}

if [[ "${1:-}" == "--help" || "${1:-}" == "-h" ]]; then
    usage
    exit 0
fi
if [[ $# -ne 0 ]]; then
    usage >&2
    exit 1
fi

fail() {
    printf 'Error: %s\n' "$*" >&2
    exit 1
}

[[ "$(uname -s)" == Darwin ]] || fail "This script requires macOS."

PROJECT_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
QT_ROOT="${QT_ROOT:-$PROJECT_ROOT/build/arm64/externals/qt/6.10.3/macos}"
BUILD_ROOT="${BUILD_ROOT:-$PROJECT_ROOT/build/macos-universal}"
JOBS="${JOBS:-8}"
SKIP_SUBMODULE_UPDATE="${SKIP_SUBMODULE_UPDATE:-0}"

[[ "$JOBS" =~ ^[1-9][0-9]*$ ]] || fail "JOBS must be a positive integer."
[[ "$SKIP_SUBMODULE_UPDATE" == 0 || "$SKIP_SUBMODULE_UPDATE" == 1 ]] ||
    fail "SKIP_SUBMODULE_UPDATE must be 0 or 1."

for tool in cmake ninja git ditto codesign lipo file xcode-select; do
    command -v "$tool" >/dev/null 2>&1 || fail "Required tool not found: $tool"
done
xcode-select -p >/dev/null || fail "Select an Xcode developer directory first."

[[ -f "$QT_ROOT/lib/cmake/Qt6/Qt6Config.cmake" ]] ||
    fail "Qt 6 was not found at $QT_ROOT. Set QT_ROOT to a universal Qt installation."
QT_ROOT="$(cd -- "$QT_ROOT" && pwd)"
if [[ -x "$QT_ROOT/bin/macdeployqt6" ]]; then
    MACDEPLOYQT="$QT_ROOT/bin/macdeployqt6"
elif [[ -x "$QT_ROOT/bin/macdeployqt" ]]; then
    MACDEPLOYQT="$QT_ROOT/bin/macdeployqt"
else
    fail "macdeployqt is missing from $QT_ROOT/bin."
fi

mkdir -p "$BUILD_ROOT"
BUILD_ROOT="$(cd -- "$BUILD_ROOT" && pwd)"
RUN_DIR="$(mktemp -d "$BUILD_ROOT/run.XXXXXX")"
trap 'printf "Build stopped. Files are preserved in: %s\n" "$RUN_DIR" >&2' ERR
printf 'Build directory: %s\n' "$RUN_DIR"

# BundleTarget.cmake looks specifically for macdeployqt6. Keep the selected Qt's
# deployment tool first on PATH, including Qt distributions without that alias.
mkdir -p "$RUN_DIR/tools"
ln -s "$MACDEPLOYQT" "$RUN_DIR/tools/macdeployqt6"
export PATH="$RUN_DIR/tools:$QT_ROOT/bin:$PATH"

ENABLE_CCACHE=OFF
if command -v ccache >/dev/null 2>&1; then
    ENABLE_CCACHE=ON
fi

if [[ "$SKIP_SUBMODULE_UPDATE" == 0 ]]; then
    git -C "$PROJECT_ROOT" submodule update --init --recursive
fi

for target_arch in arm64 x86_64; do
    output="$RUN_DIR/compile-$target_arch"
    printf '\nBuilding %s...\n' "$target_arch"

    cmake -S "$PROJECT_ROOT" -B "$output" -G Ninja \
        -DCMAKE_BUILD_TYPE=Release \
        -DCMAKE_C_COMPILER=/usr/bin/clang \
        -DCMAKE_CXX_COMPILER=/usr/bin/clang++ \
        -DCMAKE_SYSTEM_NAME=Darwin \
        -DCMAKE_SYSTEM_PROCESSOR="$target_arch" \
        -DCMAKE_OSX_ARCHITECTURES="$target_arch" \
        -DUSE_SYSTEM_QT=ON \
        -DCMAKE_PREFIX_PATH="$QT_ROOT" \
        -DQT_HOST_PATH="$QT_ROOT" \
        -DQT_TARGET_PATH="$QT_ROOT" \
        -DENABLE_QT_TRANSLATION=ON \
        -DENABLE_ROOM_STANDALONE=OFF \
        -DUSE_DISCORD_PRESENCE=ON \
        -DENABLE_CCACHE="$ENABLE_CCACHE" \
        -DENABLE_TESTS=OFF \
        -DENABLE_FFMPEG=OFF

    cmake --build "$output" --target bundle --parallel "$JOBS"

    # Copy under the case-sensitive name expected by macos-universal.sh.
    artifact="$RUN_DIR/macos-$target_arch"
    ditto "$output/bundle" "$artifact"
    mv "$artifact/azahar.app" "$artifact/app-to-rename"
    mv "$artifact/app-to-rename" "$artifact/Azahar.app"
done

printf '\nCombining architectures...\n'
(
    cd "$RUN_DIR"
    ARTIFACTS="macos-x86_64 macos-arm64" \
        /bin/bash -ex "$PROJECT_ROOT/.ci/macos-universal.sh"
)

# Archive a copy so that the universal app and all bundle resources remain
# available after packaging. ditto preserves macOS bundle metadata and symlinks.
PACKAGE_NAME=AzaharPlus-macos-universal
ARTIFACTS_DIR="$RUN_DIR/artifacts"
mkdir -p "$ARTIFACTS_DIR"
ditto "$RUN_DIR/build/bundle" "$ARTIFACTS_DIR/$PACKAGE_NAME"
ditto -c -k --sequesterRsrc --keepParent \
    "$ARTIFACTS_DIR/$PACKAGE_NAME" "$ARTIFACTS_DIR/$PACKAGE_NAME.zip"

printf '\nApp: %s\nZIP: %s\n' \
    "$ARTIFACTS_DIR/$PACKAGE_NAME/Azahar.app" \
    "$ARTIFACTS_DIR/$PACKAGE_NAME.zip"
