#!/usr/bin/env sh

ARCH="$(uname -m)"

scripts/desktop/build-lib-linux.sh
cd apps/multiplatform
if [ -n "${ASSETS_DIR:-}" ]; then
  set -- -Psimplex.assets.dir="$ASSETS_DIR"
else
  set --
fi
./gradlew "$@" packageDeb

# Workaround for skiko library
#
# Compose Multiplatform depends on skiko library, that
# handles all of the window managment and graphics drawing.
#
# This skiko library comes with two jar's:
# - platform-agnostic "skiko-awt"
# - and platform-specific "skiko-awt-runtime"
#
# In case of Linux, second jar is called "skiko-awt-runtime-linux-x64".
# Essentially, this jar has the Linux .so library called "libskiko-linux-x64.so"
# that is being unpacked to runtime libs.
#
# Since the jar is nothing more than a zip archive, extracting library
# from "skiko-awt-runtime-linux-x64" modifies it's timestamps
# with current time, which in changes it's hash, which in turn
# makes the whole build unreproducible.
#
# It seems to be there is no way to handle this extraction in our code and
# https://docs.gradle.org/current/userguide/working_with_files.html#sec:reproducible_archives
# unfortunately doesn't solve the issue.
#
# Instead, just modify the deb, removing the redundant skiko library.
#
# Also, it seems this is related to:
# https://youtrack.jetbrains.com/issue/CMP-1971/createDistributable-produces-duplicated-skiko-awt.jar-and-skiko-awt-runtime-windows-x64.jar

export SOURCE_DATE_EPOCH=1704067200

deb_file=$(find ./release/main/deb -maxdepth 1 -type f -name 'simplex*.deb' -printf '%T@ %p\n' | sort -n | tail -n 1 | cut -d' ' -f2-)
rm -rf ./extracted
dpkg-deb -R "$deb_file" ./extracted

# Source the distribution variables (VERSION_CODENAME)
. /etc/os-release

rm -f ./extracted/opt/*imple*/lib/app/*skiko-awt-runtime-linux*
sed -i -e '/skiko-awt-runtime-linux/d' ./extracted/opt/*imple*/lib/app/simplex.cfg
sed -i "/Version/ s/\$/~$VERSION_CODENAME/" ./extracted/DEBIAN/control
rm -f ./extracted/usr/share/applications/simplex.desktop
rm -f ./extracted/usr/share/applications/chat.simplex.simplex.desktop
rm -f ./extracted/opt/*imple*/lib/simplex-simplex.desktop
rm -f ./extracted/opt/*imple*/lib/simplex.desktop
desktop_dir=$(echo ./extracted/opt/*imple*/lib)
mkdir -p ./extracted/usr/share/applications
cp ./desktop/build/processedResources/jvm/main/distribute/chat.simplex.simplex.desktop "$desktop_dir/chat.simplex.simplex.desktop"
cp ./desktop/build/processedResources/jvm/main/distribute/chat.simplex.simplex.desktop ./extracted/usr/share/applications/chat.simplex.simplex.desktop
if [ -e "$desktop_dir/chat.simplex.simplex.desktop" ]; then
    chmod 644 "$desktop_dir/chat.simplex.simplex.desktop"
fi
find ./extracted/ -exec touch -d "@$SOURCE_DATE_EPOCH" {} +

dpkg-deb --build --root-owner-group --uniform-compression ./extracted ./release/main/deb/simplex_${ARCH}.deb

if command -v strip-nondeterminism >/dev/null 2>&1; then
    strip-nondeterminism ./release/main/deb/simplex_${ARCH}.deb
fi
