#!/usr/bin/env bash
set -euo pipefail

echo "Fetching ARM64 libproot.so, libproot-loader.so, and libtalloc.so.2 into app/src/main/jniLibs/arm64-v8a/..."
mkdir -p app/src/main/jniLibs/arm64-v8a
TMP_DIR=$(mktemp -d)
cd "$TMP_DIR"

PACKAGES_URL="https://packages.termux.dev/apt/termux-main/dists/stable/main/binary-aarch64/Packages"
curl -sL "$PACKAGES_URL" -o Packages

PROOT_DEB_PATH=$(awk '/^Package: proot$/,/^$/' Packages | grep '^Filename:' | awk '{print $2}')
TALLOC_DEB_PATH=$(awk '/^Package: libtalloc$/,/^$/' Packages | grep '^Filename:' | awk '{print $2}')

curl -sL "https://packages.termux.dev/apt/termux-main/$PROOT_DEB_PATH" -o proot.deb
curl -sL "https://packages.termux.dev/apt/termux-main/$TALLOC_DEB_PATH" -o talloc.deb

ar x proot.deb data.tar.xz && tar -xf data.tar.xz
ar x talloc.deb data.tar.xz && tar -xf data.tar.xz

cp data/data/com.termux/files/usr/bin/proot "$OLDPWD/app/src/main/jniLibs/arm64-v8a/libproot.so"
cp data/data/com.termux/files/usr/libexec/proot/loader "$OLDPWD/app/src/main/jniLibs/arm64-v8a/libproot-loader.so"
cp data/data/com.termux/files/usr/lib/libtalloc.so.2 "$OLDPWD/app/src/main/jniLibs/arm64-v8a/libtalloc.so.2"
chmod 755 "$OLDPWD/app/src/main/jniLibs/arm64-v8a/"*.so*

rm -rf "$TMP_DIR"
echo "Done! Native ARM64 binaries placed in app/src/main/jniLibs/arm64-v8a/."
