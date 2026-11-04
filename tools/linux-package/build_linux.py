"""
Builds Streamlet's Linux packages without a Linux machine (jpackage only targets the OS it runs on):

  streamlet-<version>-linux-x64.tar.gz   portable: extract anywhere, run streamlet/bin/streamlet
  streamlet_<version>_amd64.deb          Debian / Ubuntu / Mint: installs to /opt/streamlet, depends on libmpv

Both bundle a Linux JDK (the same Temurin 17 the Windows installer bundles). CI's jpackage .deb/.rpm (release.yml)
remain the official ones; this exists for building on Windows.

  ./gradlew --no-configuration-cache :desktop-app:linuxLibs
  python -I tools/linux-package/build_linux.py --libs desktop-app/build/packaging/linux-libs \
      --jdk OpenJDK17U-jdk_x64_linux_hotspot_17.0.19_10.tar.gz --icon desktop-app/icons/streamlet.png \
      --version 1.0.0 --out desktop-app/build/compose/binaries/main/linux
"""
import argparse, gzip, hashlib, io, os, tarfile, time

APP = "streamlet"
JVM_ARGS = "-Xmx3g --enable-native-access=ALL-UNNAMED -Dfile.encoding=UTF-8 -Dcompose.application.configure.swing.globals=true"

LAUNCHER = """#!/bin/sh
# Streamlet launcher: the bundled Java runtime, the app's jars.
HERE="$(dirname "$(readlink -f "$0")")/.."
HERE="$(cd "$HERE" && pwd)"
exec "$HERE/lib/runtime/bin/java" {jvm} -Djpackage.app-version={version} \\
  -Dcompose.application.resources.dir="$HERE/lib/app/resources" \\
  -cp "$HERE/lib/app/*" cloudstream.desktop.app.MainKt "$@"
"""

DESKTOP = """[Desktop Entry]
Type=Application
Name=Streamlet
Comment=A desktop player for CloudStream extensions
Exec={exec} %U
Icon={icon}
Terminal=false
Categories=AudioVideo;Video;Player;
StartupWMClass=cloudstream-desktop-app-MainKt
"""

CONTROL = """Package: streamlet
Version: {version}
Section: video
Priority: optional
Architecture: amd64
Maintainer: Streamlet <streamlet@users.noreply.github.com>
Depends: libmpv2 | libmpv1, libasound2t64 | libasound2, libx11-6, libxext6, libxi6, libxrender1, libxtst6, libfreetype6, libfontconfig1, zlib1g, libgl1
Installed-Size: {size_kb}
Homepage: https://github.com/Akshit-Vengala/streamlet
Description: Streamlet: a desktop player for CloudStream extensions
 Plays CloudStream extensions on the desktop with a built-in libmpv player.
"""

POSTINST = """#!/bin/sh
set -e
command -v update-desktop-database >/dev/null 2>&1 && update-desktop-database -q /usr/share/applications || true
command -v gtk-update-icon-cache >/dev/null 2>&1 && gtk-update-icon-cache -q /usr/share/icons/hicolor || true
exit 0
"""


class Tree:
    """Files for one package: path -> (mode, bytes) or a symlink (mode, None, target)."""
    def __init__(self):
        self.entries = {}

    def add(self, path, data, mode=0o644):
        self.entries[path] = (mode, data, None)

    def link(self, path, target):
        self.entries[path] = (0o777, None, target)

    def size(self):
        return sum(len(d) for _, d, _ in self.entries.values() if d is not None)

    def tar(self, prefix="", root_owned=True):
        buf = io.BytesIO()
        now = int(time.time())
        with tarfile.open(fileobj=buf, mode="w:gz", format=tarfile.GNU_FORMAT, compresslevel=9) as t:
            dirs = set()
            for p in self.entries:
                parts = (prefix + p).strip("/").split("/")[:-1]
                for i in range(1, len(parts) + 1):
                    dirs.add("/".join(parts[:i]))
            def info(name, mode, kind):
                ti = tarfile.TarInfo(("./" if root_owned else "") + name)
                ti.mode, ti.mtime, ti.type = mode, now, kind
                ti.uid = ti.gid = 0
                ti.uname = ti.gname = "root" if root_owned else ""
                return ti
            for d in sorted(dirs):
                t.addfile(info(d, 0o755, tarfile.DIRTYPE))
            for p in sorted(self.entries):
                mode, data, target = self.entries[p]
                name = (prefix + p).strip("/")
                if target is not None:
                    ti = info(name, mode, tarfile.SYMTYPE); ti.linkname = target; t.addfile(ti)
                else:
                    ti = info(name, mode, tarfile.REGTYPE); ti.size = len(data); t.addfile(ti, io.BytesIO(data))
        return buf.getvalue()


def add_jdk(tree, jdk_tgz, dest):
    """The JDK tarball's contents (minus docs, sources, demos) under dest, modes and symlinks kept."""
    with tarfile.open(jdk_tgz, "r:gz") as t:
        for m in t.getmembers():
            parts = m.name.split("/", 1)
            if len(parts) < 2 or not parts[1]:
                continue
            rel = parts[1]
            if rel.startswith(("lib/src.zip", "demo/", "man/", "sample/")) or rel == "lib/src.zip":
                continue
            if m.issym():
                tree.link(dest + rel, m.linkname)
            elif m.isfile():
                tree.add(dest + rel, t.extractfile(m).read(), m.mode & 0o777)


def ar(members):
    """A Debian package is an `ar` archive: debian-binary, control.tar.gz, data.tar.gz."""
    out = io.BytesIO()
    out.write(b"!<arch>\n")
    for name, data in members:
        hdr = f"{name:<16}{int(time.time()):<12}{0:<6}{0:<6}{'100644':<8}{len(data):<10}`\n".encode()
        out.write(hdr); out.write(data)
        if len(data) % 2: out.write(b"\n")
    return out.getvalue()


def main():
    a = argparse.ArgumentParser()
    a.add_argument("--libs", required=True); a.add_argument("--jdk", required=True)
    a.add_argument("--icon", required=True); a.add_argument("--version", required=True)
    a.add_argument("--out", required=True)
    o = a.parse_args()
    os.makedirs(o.out, exist_ok=True)
    jars = sorted(f for f in os.listdir(o.libs) if f.endswith(".jar"))
    assert any(j.startswith("skiko-awt-runtime-linux-x64") for j in jars), "no Linux skiko native: run :desktop-app:linuxLibs"
    assert not any(j.startswith("skiko-awt-runtime-windows") for j in jars), "Windows skiko native in the Linux libs"
    icon = open(o.icon, "rb").read()
    launcher = LAUNCHER.format(jvm=JVM_ARGS, version=o.version).encode()

    def app_tree(base):
        t = Tree()
        t.add(base + "bin/" + APP, launcher, 0o755)
        for j in jars:
            t.add(base + "lib/app/" + j, open(os.path.join(o.libs, j), "rb").read())
        t.add(base + "lib/app/resources/.keep", b"")
        t.add(base + "lib/" + APP + ".png", icon)
        add_jdk(t, o.jdk, base + "lib/runtime/")
        return t

    # Portable tarball.
    port = app_tree("")
    port.add("streamlet.desktop", DESKTOP.format(exec="streamlet", icon="streamlet").encode())
    port.add("README.txt", (
        "Streamlet {v} for Linux (x86_64)\n\nRun:  ./bin/streamlet\n\n"
        "Video playback needs libmpv from your distribution:\n"
        "  Debian/Ubuntu: sudo apt install libmpv2   (Ubuntu 22.04: libmpv1)\n"
        "  Fedora:        sudo dnf install mpv-libs\n  Arch:          sudo pacman -S mpv\n\n"
        "Menu entry (optional): edit Exec= and Icon= in streamlet.desktop to this folder's bin/streamlet and\n"
        "lib/streamlet.png, then copy it to ~/.local/share/applications/.\n").format(v=o.version).encode())
    tgz = os.path.join(o.out, f"streamlet-{o.version}-linux-x64.tar.gz")
    open(tgz, "wb").write(port.tar(prefix="streamlet/", root_owned=False))

    # .deb
    data = app_tree("opt/streamlet/")
    data.link("usr/bin/streamlet", "/opt/streamlet/bin/streamlet")
    data.add("usr/share/applications/streamlet.desktop", DESKTOP.format(exec="/opt/streamlet/bin/streamlet", icon="streamlet").encode())
    data.add("usr/share/icons/hicolor/512x512/apps/streamlet.png", icon)
    ctl = Tree()
    ctl.add("control", CONTROL.format(version=o.version, size_kb=data.size() // 1024).encode())
    ctl.add("postinst", POSTINST.encode(), 0o755)
    ctl.add("postrm", POSTINST.encode(), 0o755)
    md5 = "".join(f"{hashlib.md5(d).hexdigest()}  {p}\n" for p, (_, d, tg) in sorted(data.entries.items()) if d is not None)
    ctl.add("md5sums", md5.encode())
    deb = os.path.join(o.out, f"streamlet_{o.version}_amd64.deb")
    open(deb, "wb").write(ar([("debian-binary", b"2.0\n"), ("control.tar.gz", ctl.tar()), ("data.tar.gz", data.tar())]))

    for f in (tgz, deb):
        print(f"{f}  {os.path.getsize(f) // (1 << 20)} MB  sha256 {hashlib.sha256(open(f, 'rb').read()).hexdigest()}")


if __name__ == "__main__":
    main()
