#!/bin/bash -ex

BUILD_ARCH="${BUILD_ARCH:-$(uname -m)}"
# The project downloads its tested Qt toolchain unless a developer explicitly
# opts into a system installation and supplies its CMake prefix separately.
USE_SYSTEM_QT="${USE_SYSTEM_QT:-OFF}"

if [ "$GITHUB_REF_TYPE" == "tag" ]; then
	export EXTRA_CMAKE_FLAGS=(-DENABLE_QT_UPDATE_CHECKER=ON)
fi

mkdir -p build/$BUILD_ARCH && cd build/$BUILD_ARCH
cmake ../.. -GNinja \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_OSX_ARCHITECTURES="$BUILD_ARCH" \
    -DUSE_SYSTEM_QT="$USE_SYSTEM_QT" \
    -DENABLE_ROOM_STANDALONE=OFF \
    -DENABLE_DISCORD_RPC=ON \
	"${EXTRA_CMAKE_FLAGS[@]}"
ninja
ninja bundle
mv ./bundle/azahar.app ./bundle/Azahar.app # TODO: Can this be done in CMake?


CURRENT_ARCH=`arch`
if [ "$BUILD_ARCH" = "$CURRENT_ARCH" ]; then
  ctest -VV -C Release
fi
