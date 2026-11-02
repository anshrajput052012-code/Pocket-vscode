#!/bin/bash
# Pocket VS Code — session launcher (runs INSIDE the PRoot Debian ARM64 rootfs).
#
#   x11    : Mode 1 — Xtigervnc :1 (size chosen in the app, see GEOMETRY) + Openbox + noVNC + full Electron VS Code Desktop.
#            Never throttled, no watcher.
#   direct : Mode 2 — official `code serve-web` on 127.0.0.1:8080 with the battery saver:
#            the app refreshes /tmp/.pocket-mode2-heartbeat while a Mode 2 window is open; when it has not been
#            refreshed for 10 minutes the server is hibernated with SIGTERM (0 % background drain). Re-opening
#            Mode 2 in the app starts it again; auto-save (0.5 s) + hot-exit keep the editor state intact.

MODE="${1:-x11}"
PORT=8080
HEARTBEAT="/tmp/.pocket-mode2-heartbeat"
IDLE_LIMIT=600     # seconds without an app heartbeat before Mode 2 hibernates
CHECK_EVERY=30     # watcher poll interval in seconds

mkdir -p /tmp /dev/shm /tmp/.X11-unix /root/.config/Code/User /root/.vscode/extensions \
    /root/.vscode-server-data/data/Machine /root/.vscode-server-data/data/User \
    /root/Workspace /usr/local/bin
chmod 1777 /tmp /dev/shm /tmp/.X11-unix 2>/dev/null || true

# Some tools call sudo; we are already (fake) root inside PRoot.
if [ ! -f /usr/local/bin/sudo ]; then
    printf '#!/bin/sh\nexec "$@"\n' > /usr/local/bin/sudo
    chmod 755 /usr/local/bin/sudo
fi

# The `code` launcher sniffs /proc/version for WSL, which Android's SELinux policy may not allow.
if [ -f /opt/vscode-arm64/bin/code ] && grep -q '/proc/version' /opt/vscode-arm64/bin/code 2>/dev/null; then
    sed -i 's|/proc/version|/dev/null|g' /opt/vscode-arm64/bin/code || true
fi

# Run the VS Code UI as a normal user: no "[Superuser]" tag / root warning (patched once per extraction).
if [ ! -f /opt/vscode-arm64/.noroot_patched ] && [ -d /opt/vscode-arm64/resources/app/out ]; then
    find /opt/vscode-arm64/resources/app/out -name "preload*.js" -exec sed -i 's/process\.getuid()/1000/g' {} + || true
    WB_JS="/opt/vscode-arm64/resources/app/out/vs/workbench/workbench.desktop.main.js"
    if [ -f "$WB_JS" ]; then
        sed -i 's/\.getuid\?\.()===0/false/g; s/\.getuid()===0/false/g; s/ \[Superuser\]//g; s/\[Superuser\]//g' "$WB_JS" || true
    fi
    touch /opt/vscode-arm64/.noroot_patched
fi

# ------------------------------------------------------------------------------------------------
# Mode 2 — native web host with battery saver
# ------------------------------------------------------------------------------------------------
if [ "$MODE" = "direct" ]; then
    # Already serving (e.g. the app re-attached)? Do not start a second server; stay attached until it exits.
    if pgrep -f "serve-web" >/dev/null 2>&1; then
        echo "[start-vscode] Mode 2 server already running - resuming."
        while pgrep -f "serve-web" >/dev/null 2>&1; do
            sleep 5
        done
        exit 0
    fi

    touch "$HEARTBEAT"
    echo "[start-vscode] Starting Official VS Code Native Workbench Host (code serve-web) on 127.0.0.1:${PORT} ..."

    nice -n 10 /opt/vscode-arm64/bin/code serve-web \
        --host 127.0.0.1 \
        --port "$PORT" \
        --without-connection-token \
        --accept-server-license-terms \
        --server-data-dir /root/.vscode-server-data &
    SERVER_PID=$!

    # Idle watcher: hibernate gracefully (SIGTERM) when the app stopped refreshing the heartbeat.
    (
        while kill -0 "$SERVER_PID" 2>/dev/null; do
            sleep "$CHECK_EVERY"
            NOW=$(date +%s)
            LAST=$(stat -c %Y "$HEARTBEAT" 2>/dev/null || echo "$NOW")
            if [ $((NOW - LAST)) -ge "$IDLE_LIMIT" ]; then
                echo "[battery-saver] Mode 2 inactive for $((IDLE_LIMIT / 60)) minutes - hibernating the server to save battery."
                pkill -TERM -f "serve-web" 2>/dev/null
                pkill -TERM -f "server-main" 2>/dev/null
                sleep 8
                pkill -KILL -f "serve-web" 2>/dev/null
                pkill -KILL -f "server-main" 2>/dev/null
                exit 0
            fi
        done
    ) &
    WATCHER_PID=$!

    wait "$SERVER_PID"
    STATUS=$?
    kill "$WATCHER_PID" 2>/dev/null
    exit "$STATUS"
fi

# ------------------------------------------------------------------------------------------------
# Mode 1 — full Electron desktop (unthrottled)
# ------------------------------------------------------------------------------------------------
pkill -9 -f Xtigervnc 2>/dev/null
pkill -9 -f websockify 2>/dev/null
pkill -9 -f openbox 2>/dev/null
rm -rf /tmp/.X1-lock /tmp/.X11-unix/X1
sleep 1

# Screen size of the virtual desktop. The app writes the user's choice (Balanced 1440x900 / Sharp 1920x1200 /
# Native = the tablet's own size) to /root/.pocket-geometry before every Mode 1 start. Bigger = sharper but heavier.
GEOMETRY="1920x1200"
if [ -f /root/.pocket-geometry ]; then
    CUSTOM="$(tr -d ' \r\n' < /root/.pocket-geometry)"
    if printf '%s' "$CUSTOM" | grep -Eq '^[0-9]{3,4}x[0-9]{3,4}$'; then GEOMETRY="$CUSTOM"; fi
fi

echo "[start-vscode] Starting Embedded X11 Server (Xtigervnc :1, $GEOMETRY) & Openbox Window Manager..."
Xtigervnc :1 \
    -geometry "$GEOMETRY" \
    -depth 24 \
    -localhost \
    -SecurityTypes None \
    -AlwaysShared &
sleep 2

export DISPLAY=:1
openbox &
websockify --web=/usr/share/novnc/ 127.0.0.1:6080 127.0.0.1:5901 &
sleep 1

echo "VSCODE_DISPLAY_READY"
exec dbus-run-session -- /opt/vscode-arm64/bin/code \
    --no-sandbox \
    --disable-gpu \
    --disable-gpu-sandbox \
    --disable-dev-shm-usage \
    --password-store="basic" \
    --user-data-dir="/root/.config/Code" \
    --extensions-dir="/root/.vscode/extensions" \
    --wait \
    "/root/Workspace"
