#!/bin/bash -ex

apply_dependency_patch() {
    local dependency="$1"
    local patch_file="$(pwd)/.ci/patches/$2"
    if ! git -C "externals/$dependency" apply --reverse --check "$patch_file" >/dev/null 2>&1; then
        git -C "externals/$dependency" apply "$patch_file"
    fi
}

# Dynarmic's IR emitter header uses std::vector without including <vector>.
apply_dependency_patch dynarmic dynarmic-include-vector.patch
# LibreSSL's Windows endian shim must define conversions even if endian.h exists.
apply_dependency_patch libressl libressl-windows-endian.patch

mkdir -p build
cd build

EXTRA_CMAKE_FLAGS=()
if [ "$GITHUB_REF_TYPE" == "tag" ]; then
    EXTRA_CMAKE_FLAGS+=(-DENABLE_QT_UPDATE_CHECKER=ON)
fi

if [ "$TARGET" == "msvc" ] && [ -n "$FFMPEG_ROOT" ]; then
    # Also exposes the FFmpeg DLL directory to the bundle target.
    EXTRA_CMAKE_FLAGS+=("-DCMAKE_PREFIX_PATH=$FFMPEG_ROOT")
fi

if [ "$TARGET" == "msvc" ] && [ -n "$ZLIB_ROOT" ]; then
    EXTRA_CMAKE_FLAGS+=("-DZLIB_ROOT=$ZLIB_ROOT" -DZLIB_USE_STATIC_LIBS=ON)
fi

cmake .. -G Ninja \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_C_COMPILER_LAUNCHER=ccache \
    -DCMAKE_CXX_COMPILER_LAUNCHER=ccache \
    -DENABLE_QT_TRANSLATION=ON \
    -DUSE_DISCORD_PRESENCE=ON \
    -DCITRA_WARNINGS_AS_ERRORS=OFF \
    "${EXTRA_CMAKE_FLAGS[@]}"
# Finish independent compilation steps so a single CI run reports more failures.
ninja -k 0
ninja bundle
strip -s bundle/*.exe

ccache -s -v

ctest -VV -C Release || echo "::error ::Test error occurred on Windows build"
