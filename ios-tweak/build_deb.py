#!/usr/bin/env python3
"""
Builds PingRelay.dylib + PingRelayPrefs.bundle (arm64, iOS 15.0+)
using Xcode's iphoneos SDK, ad-hoc signs them with codesign, and packages a
Dopamine rootless Debian package:
  build/uk.maynotfunction.pingrelay_<Version>_iphoneos-arm64.deb
The version comes from the `control` file.
"""
import io
import subprocess
import tarfile
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent
BUILD = ROOT / "build"


def control_version():
    for line in (ROOT / "control").read_text().splitlines():
        if line.startswith("Version:"):
            return line.split(":", 1)[1].strip()
    raise SystemExit("No Version: line in control")


def run(cmd):
    print("+", " ".join(cmd))
    subprocess.run(cmd, check=True)


def make_tar_gz(entries):
    buf = io.BytesIO()
    mtime = int(time.time())
    with tarfile.open(fileobj=buf, mode="w:gz", format=tarfile.GNU_FORMAT) as tf:
        for arcname, mode, content in entries:
            ti = tarfile.TarInfo(name=arcname)
            ti.mtime = mtime
            ti.uid = 0
            ti.gid = 0
            ti.uname = "root"
            ti.gname = "wheel"
            if content is None:
                ti.type = tarfile.DIRTYPE
                ti.mode = mode
                tf.addfile(ti)
            else:
                ti.type = tarfile.REGTYPE
                ti.mode = mode
                ti.size = len(content)
                tf.addfile(ti, io.BytesIO(content))
    return buf.getvalue()


def write_ar_deb(deb_path: Path, files):
    with open(deb_path, "wb") as f:
        f.write(b"!<arch>\n")
        mtime = int(time.time())
        for name, data in files:
            header = (
                f"{name:<16}"
                f"{mtime:<12}"
                f"{0:<6}"
                f"{0:<6}"
                f"{0o100644:<8o}"
                f"{len(data):<10}"
                "`\n"
            ).encode("ascii")
            f.write(header)
            f.write(data)
            if len(data) % 2 == 1:
                f.write(b"\n")


def main():
    BUILD.mkdir(parents=True, exist_ok=True)
    version = control_version()
    dylib_path = BUILD / "PingRelay.dylib"

    sdk_path = subprocess.check_output(
        ["xcrun", "--sdk", "iphoneos", "--show-sdk-path"], text=True
    ).strip()
    clang_path = subprocess.check_output(
        ["xcrun", "--sdk", "iphoneos", "-f", "clang"], text=True
    ).strip()

    run([
        clang_path,
        "-arch", "arm64",
        "-isysroot", sdk_path,
        "-miphoneos-version-min=15.0",
        "-dynamiclib",
        "-fobjc-arc",
        "-O2",
        "-Wall",
        "-Wextra",
        "-Wno-unused-parameter",
        "-framework", "Foundation",
        "-framework", "UIKit",
        "-framework", "CoreLocation",
        "-framework", "Security",
        "-install_name", "/var/jb/Library/MobileSubstrate/DynamicLibraries/PingRelay.dylib",
        "-I", str(ROOT),
        str(ROOT / "Tweak.m"),
        "-o", str(dylib_path),
    ])

    run(["codesign", "-f", "-s", "-", str(dylib_path)])

    control_bytes = (ROOT / "control").read_bytes()
    postinst_script = (
        b"#!/bin/sh\n"
        b"killall -9 fmfd findmydeviced searchpartyd FindMy Preferences 2>/dev/null || true\n"
        b"exit 0\n"
    )
    control_tar_gz = make_tar_gz([
        ("./", 0o755, None),
        ("./control", 0o644, control_bytes),
        ("./postinst", 0o755, postinst_script),
    ])

    dylib_bytes = dylib_path.read_bytes()
    plist_bytes = (ROOT / "PingRelay.plist").read_bytes()

    pref_loader_entry = (
        b'<?xml version="1.0" encoding="UTF-8"?>\n'
        b'<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">\n'
        b'<plist version="1.0">\n'
        b'<dict>\n'
        b'    <key>entry</key>\n'
        b'    <dict>\n'
        b'        <key>bundle</key>\n'
        b'        <string>PingRelayPrefs</string>\n'
        b'        <key>cell</key>\n'
        b'        <string>PSLinkCell</string>\n'
        b'        <key>detail</key>\n'
        b'        <string>FMBSettingsViewController</string>\n'
        b'        <key>isController</key>\n'
        b'        <true/>\n'
        b'        <key>label</key>\n'
        b'        <string>Ping Relay</string>\n'
        b'    </dict>\n'
        b'</dict>\n'
        b'</plist>\n'
    )

    v = version.encode("ascii")
    bundle_info_plist = (
        b'<?xml version="1.0" encoding="UTF-8"?>\n'
        b'<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">\n'
        b'<plist version="1.0">\n'
        b'<dict>\n'
        b'    <key>CFBundleExecutable</key>\n'
        b'    <string>PingRelayPrefs</string>\n'
        b'    <key>CFBundleIdentifier</key>\n'
        b'    <string>uk.maynotfunction.pingrelay.prefs</string>\n'
        b'    <key>CFBundleInfoDictionaryVersion</key>\n'
        b'    <string>6.0</string>\n'
        b'    <key>CFBundleName</key>\n'
        b'    <string>PingRelayPrefs</string>\n'
        b'    <key>CFBundlePackageType</key>\n'
        b'    <string>BNDL</string>\n'
        b'    <key>CFBundleShortVersionString</key>\n'
        b'    <string>' + v + b'</string>\n'
        b'    <key>CFBundleVersion</key>\n'
        b'    <string>' + v + b'</string>\n'
        b'    <key>NSPrincipalClass</key>\n'
        b'    <string>FMBSettingsViewController</string>\n'
        b'</dict>\n'
        b'</plist>\n'
    )

    data_tar_gz = make_tar_gz([
        ("./", 0o755, None),
        ("./var/", 0o755, None),
        ("./var/jb/", 0o755, None),
        ("./var/jb/Library/", 0o755, None),
        ("./var/jb/Library/MobileSubstrate/", 0o755, None),
        ("./var/jb/Library/MobileSubstrate/DynamicLibraries/", 0o755, None),
        (
            "./var/jb/Library/MobileSubstrate/DynamicLibraries/PingRelay.dylib",
            0o755,
            dylib_bytes,
        ),
        (
            "./var/jb/Library/MobileSubstrate/DynamicLibraries/PingRelay.plist",
            0o644,
            plist_bytes,
        ),
        ("./var/jb/Library/PreferenceLoader/", 0o755, None),
        ("./var/jb/Library/PreferenceLoader/Preferences/", 0o755, None),
        (
            "./var/jb/Library/PreferenceLoader/Preferences/PingRelay.plist",
            0o644,
            pref_loader_entry,
        ),
        ("./var/jb/Library/PreferenceBundles/", 0o755, None),
        ("./var/jb/Library/PreferenceBundles/PingRelayPrefs.bundle/", 0o755, None),
        (
            "./var/jb/Library/PreferenceBundles/PingRelayPrefs.bundle/Info.plist",
            0o644,
            bundle_info_plist,
        ),
        (
            "./var/jb/Library/PreferenceBundles/PingRelayPrefs.bundle/PingRelayPrefs",
            0o755,
            dylib_bytes,
        ),
    ])

    # Remove debs from earlier versions so an old one can't be installed by mistake.
    for old in BUILD.glob("uk.maynotfunction.pingrelay_*_iphoneos-arm64.deb"):
        old.unlink()
    deb_out = BUILD / f"uk.maynotfunction.pingrelay_{version}_iphoneos-arm64.deb"
    write_ar_deb(
        deb_out,
        [
            ("debian-binary", b"2.0\n"),
            ("control.tar.gz", control_tar_gz),
            ("data.tar.gz", data_tar_gz),
        ],
    )
    print(f"Built rootless package with Settings pane: {deb_out} ({deb_out.stat().st_size} bytes)")


if __name__ == "__main__":
    main()
