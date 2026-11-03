Drop `libmpv-2.dll` (or `mpv-2.dll`) here. Binaries are not committed (see .gitignore).
Get it from a shinchiro/zhongfly Windows build: https://sourceforge.net/projects/mpv-player-windows/files/libmpv/

Lookup order for the DLL: env `CLOUDSTREAM_MPV_DLL`, system property `cloudstream.mpv.dll`, `player/native/`, PATH.
For the fallback player, `mpv.exe` is located via `CLOUDSTREAM_MPV_EXE`, `player/native/`, PATH; then VLC in Program Files.
