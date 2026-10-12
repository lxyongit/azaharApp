#!/bin/bash -ex

# Dynarmic's IR emitter header uses std::vector without including <vector>.
DYNARMIC_PATCH="$(pwd)/.ci/patches/dynarmic-include-vector.patch"
if ! git -C externals/dynarmic apply --reverse --check "$DYNARMIC_PATCH" >/dev/null 2>&1; then
    git -C externals/dynarmic apply "$DYNARMIC_PATCH"
fi

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

cmake .. -G Ninja \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_C_COMPILER_LAUNCHER=ccache \
    -DCMAKE_CXX_COMPILER_LAUNCHER=ccache \
    -DENABLE_QT_TRANSLATION=ON \
    -DUSE_DISCORD_PRESENCE=ON \
	"${EXTRA_CMAKE_FLAGS[@]}"
ninja
ninja bundle
strip -s bundle/*.exe

ccache -s -v

ctest -VV -C Release || echo "::error ::Test error occurred on Windows build"
