# Pocket VS Code — genuine VS Code (linux-arm64) inside one Android APK, no Termux

Package id: **`com.pocket.vscode`** (locked). Every build is signed with the same permanent keystore and gets
`versionCode = GitHub run number + VERSION_CODE_OFFSET` (offset is set at the top of the workflow, default `1000`),
so a new APK always installs as an **in-place update** (no uninstall, no "App not installed").

## What the user sees
1. **Step 1 – pick a project folder.** The moment a folder is chosen, the app extracts the bundled Debian ARM64
   rootfs and the official Microsoft VS Code, then configures the environment — automatically, in a background
   service, with a live **0 % → 100 %** progress screen. Closing the app mid-way is safe: it resumes on next start.
2. **Launch** one of two modes:
   * **Mode 1 – Full Electron Desktop**: Xtigervnc `:1` (1920x1080) + Openbox + `/opt/vscode-arm64/bin/code --no-sandbox --disable-gpu`
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

* `icon.png` (optional, repo root): your own launcher icon. If absent, the workflow downloads the official VS Code icon.
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

## Network notes
* First-run setup step 3/3 runs `apt-get` inside the rootfs → needs internet **once**.
* The first Mode 2 start may download the matching VS Code web server build (done by `code serve-web` itself).
