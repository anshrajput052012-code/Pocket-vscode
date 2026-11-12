# Pocket VS Code — genuine VS Code (linux-arm64) inside one Android APK, no Termux

Package id: **`com.anshrajput.pocketvscode`** (locked). Every build is signed with the same permanent keystore and gets
`versionCode = GitHub run number + VERSION_CODE_OFFSET` (offset is set at the top of the workflow, default `1000`),
so a new APK always installs as an **in-place update** (no uninstall, no "App not installed").

## What the user sees
1. **Step 1 – pick a project folder.** The moment a folder is chosen, the app unpacks the bundled Debian ARM64
   rootfs and the official Microsoft VS Code — automatically, in a background service, with a live **0 % → 100 %**
   progress screen. Everything else (X11/VNC, git, compilers, Python, Node.js …) is **already installed inside the
   bundled rootfs by the GitHub Actions build**, so the phone needs **no internet and no apt** and the first start
   takes only the unpacking time (a minute or two instead of ~20 min). Closing the app mid-way is safe: it resumes on
   next start.
2. **Launch** one of two modes:
   * **Mode 1 – Full Electron Desktop**: Xtigervnc `:1` (screen size chosen in the app: **Balanced** 1440x900 / **Sharp** 1920x1200 (default) / **Native** = this screen's own size) + Openbox + `/opt/vscode-arm64/bin/code --no-sandbox --disable-gpu`
     shown through noVNC. Not throttled.
   * **Mode 2 – High-FPS native web host**: `code serve-web` on `127.0.0.1:8080`. As soon as the port answers, the
     workbench opens by itself in a full-screen in-app WebView (`WorkbenchActivity`, `domStorage` on); if the WebView
     is unavailable or keeps failing, the same URL is opened in the system browser. Battery saver: 0.5 s auto-save,
     hot-exit + window restore, WebView paused in the background, and the server hibernates (SIGTERM) after 10
     minutes without an open Mode 2 window — reopening restarts it and restores the same file/tab/line.

## Repository layout
Files are stored flat, with `__` standing for `/` (e.g. `app__src__main__AndroidManifest.xml` →
`app/src/main/AndroidManifest.xml`). The workflow restores the real folder structure. Upload **all** `app__*`
files, the workflow, and `app/keystore.jks`.

* `icon.png` (optional, repo root): your own launcher icon. If absent, the workflow takes the **official, branded
  Microsoft VS Code icon from inside the VS Code archive it already downloads** (the `code.png` in the open-source
  GitHub repo is the "Code - OSS" artwork, not the real logo, so it is not used).
* `app/keystore.jks`: the permanent signing key (also saved to every GitHub Release as `pocket-vscode-permanent.keystore`).

## Moving to a NEW repository (keeps in-place updates working)
Android only installs an update when (a) the **signing key is identical** and (b) the **versionCode is higher** than
the installed one. A fresh repository restarts the GitHub run number at 1, so:
1. Upload the whole tree **including `app/keystore.jks`** (the same file as the old repo). Without it the workflow
   would generate a new key and the new APK could not update the installed app (it would have to be uninstalled).
2. Keep `VERSION_CODE_OFFSET` in `.github/workflows/build-apk.yml` high enough: the new repo's first build is
   versionCode `1 + offset`, which must be above the highest versionCode ever installed (old repo: ≤ 29). The default
   `1000` is safe. Never lower it afterwards.
3. Prefer a **private** repository: the keystore is committed and attached to Releases, so in a public repo anyone
   could sign an APK that Android would accept as an update of this app.
4. Enable *Settings → Actions → General → Workflow permissions → Read and write* so the workflow can create releases.

## Build
Push to `main` (or run the workflow manually): *Actions → Build Standalone VS Code APK*. The APK is published under
**Releases**. Local builds additionally need `fetch-native-proot.sh`, the bundled assets and generated icons, so the
GitHub Actions build is the supported path.

## Both modes share everything
* **Project files** live in the same folder (`/root/Workspace`) in both modes.
* **Settings, keybindings, snippets and extensions** are shared: Mode 2's server folders are relative symbolic links
  to the desktop (Mode 1) folders (`~/.config/Code/User`, `~/.vscode/extensions`), so what you change in one mode is
  there in the other. (Open tabs of Mode 2 are kept by the browser view and are not shared. Because the settings are
  shared, the 0.5 s auto-save of Mode 2 also applies to Mode 1.)
* **One mode at a time:** launching a mode stops the other one first, so the shared databases are never open twice.
  Stopping Mode 1 / Mode 2 this way does not lose files if auto-save is on (it is, by default).

## If Mode 1 crashes
* The start script now restarts VS Code automatically after a crash (up to ten quick crashes in a row) and keeps the
  virtual screen and the noVNC bridge alive, so the page in the app no longer turns into "Web page not available".
  The log shows the exit code, e.g. `exited with code 137 (signal 9)` = killed by Android.
* PRoot is started with `--sysvipc` when the bundled proot supports it, and the virtual screen runs without MIT-SHM
  (both avoid `XIO: fatal IO error 38 (Function not implemented)`).
* Android 12+ kills "phantom" child processes of apps that spawn many (Electron does). On Android 14/15 turn on
  **Settings → Developer options → Disable child process restrictions** (then reboot) if crashes continue.

## How the fast first start works (CI "pre-bake")
The workflow downloads the Debian arm64 rootfs, runs `app/src/main/assets/init-rootfs.sh` inside it on the GitHub
runner (arm64 emulation via qemu), trims docs/man pages/locales/apt caches, turns big hard-linked files into symlinks,
and packs the result as `debian-rootfs.tar.gz` (gzip unpacks much faster than xz) plus a `rootfs-baked.flag` marker.
The CI build takes longer (qemu), the phone is much faster.

* `POCKET_DEV_TOOLS` (env of the *Pre-bake* step in `.github/workflows/build-apk.yml`): `"1"` = compiler, gdb, git,
  Python, Node.js/npm (default); `"0"` = git only → smaller APK and faster unpacking; add tools later in the VS Code
  terminal with `apt install …`.
* If a rootfs without the baked marker is ever used, the app falls back to running `init-rootfs.sh` with apt on the
  phone (needs internet once), exactly as before.

## Network notes
* The first Mode 2 start may download the matching VS Code web server build (done by `code serve-web` itself).

## Native Mode 1 viewer, smaller APK, storage cleanup

* **Native viewer (default for Mode 1).** `RfbClient.kt` (RFB/VNC client, Raw + CopyRect + extended clipboard) and `VncView.kt`
  (touch / mouse / S Pen / keyboard / on-screen keyboard) draw the Xtigervnc desktop directly: no WebView, no noVNC, no
  websockify, real Android clipboard sync in both directions. The old noVNC page stays available as a fallback
  ("Mode 1 viewer" in the app; stored as `/root/.pocket-viewer`, read by `start-vscode-session.sh`).
  Touch: tap = click, hold then drag = select, drag = scroll (momentum), two fingers = scroll, two-finger tap = right click.
  Mouse/touchpad/S Pen work like on a computer (S Pen button = right click). Floating buttons on the right edge:
  `KB` on-screen keyboard, `Keys` Esc/Tab/Ctrl/Alt/Shift/Win/arrows/F1-F12/Copy/Paste row, `Fit` (with the keyboard up:
  whole desktop vs. zoomed to width following the pointer).
* **Latency.** Direct loopback TCP, Raw encoding, TCP_NODELAY, vsync-aligned drawing, and Xtigervnc `-FrameRate=120`.
* **Size.** CI re-packs the Debian rootfs and the VS Code archive with zstd (-19): the APK is roughly a quarter smaller and
  unpacking is not slower than gzip. The app picks the format from the file's magic number (zstd / gzip / xz).
  Updating an installed app never re-extracts anything.
* **Storage.** Old Mode 2 server downloads (`~/.vscode/cli/serve-web`, up to five versions of a few hundred MB, never used
  again) are removed once they are a day old, the newest is always kept; old logs go too. "Free up space" in the app does it
  at once and also clears download/compiler/web-page caches. "Storage report" lists the biggest folders (MB on disk) in the
  log. Mode 2 itself is unchanged.
* **Shared files.** Mode 2's downloaded server and the VS Code desktop in /opt contain many identical files; they are
  merged into hard links (same content and permissions only, atomic swap, never while VS Code runs) and the unused ~45 MB of
  Electron translations are removed. The "Storage report" also lists the biggest single files.
* **Less lag (Mode 1).** "Fast cores only" (Mode 1 options, on by default): on phones/tablets with clearly separate fast and
  slow CPU cores the virtual screen and VS Code are pinned to the fast cores (`taskset`, chosen from `cpuinfo_max_freq`; if the
  layout is unclear nothing is pinned). VS Code also starts with `--disable-smooth-scrolling`, which removes the scroll
  animation that has to be sent as many frames. Mode 2 is not affected. Tips: "Balanced" quality sends fewer pixels; keep
  big projects (node_modules, builds) in the Linux home (`/root/projects`) instead of `/root/Workspace` (that folder is the
  Android shared storage, which is slow for thousands of small files).
* **Switching folders in Mode 1.** VS Code is started with `code --wait`, and that launcher also returns (exit code 0) when the
  window is switched to another folder (File > Open Folder, Open Recent, ...). The start script used to take that for "VS Code
  was closed" and ended the whole session, which looked like a crash. It now keeps the session while the Electron main process
  (`/opt/vscode-arm64/code`, not its `--type=` helpers or the `cli.js` launcher) is still running, and ends it only when VS Code
  really quits.
* **Clipboard in Mode 2 (online).** A WebView shows no permission prompt for `navigator.clipboard`, so the workbench's own
  Paste (Edit menu, editor context menu, terminal) was always refused. `WorkbenchActivity` now installs an origin-restricted
  message channel (`WebViewCompat.addWebMessageListener`, only for `http://127.0.0.1:8080`) plus a document-start script that
  routes `navigator.clipboard.readText / writeText / read / write` and the clipboard permission query to the Android
  clipboard. Keyboard paste and the long-press paste menu are untouched; on a WebView too old for these features the bridge is
  skipped (logged) and nothing changes.
* **Continue where you left off.** Auto-save (0.5 s), hot exit (unsaved edits are kept as backups) and window restore are now
  also written to the desktop's settings file before Mode 1 starts (Mode 2 already did this; the file is shared). Mode 1 starts
  on the folder VS Code had open last (read from `globalStorage/storage.json`; first start or a deleted folder: the project
  folder), so its tabs, cursor positions and unsaved edits return with it. Mode 2 remembers the last
  `?folder=` / `?workspace=` of the workbench (kept in the app's preferences, checked for existence) and loads it instead of
  always `/root/Workspace`; when the screen is left the page keeps running for 8 more seconds so it can write its latest editor
  state before it is paused or destroyed.
