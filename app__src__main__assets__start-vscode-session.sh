#!/bin/bash
# Pocket VS Code — session launcher (runs INSIDE the PRoot Debian ARM64 rootfs).
#
#   x11    : Mode 1 — Xtigervnc :1 (size chosen in the app, see GEOMETRY) + Openbox + full Electron VS Code Desktop.
#            The app shows the desktop itself (native viewer, straight on port 5901); only when the "web" viewer is
#            chosen (/root/.pocket-viewer) is noVNC (websockify on 6080) started as well. Never throttled, no watcher.
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

# Which viewer the app uses: "native" (VncView, talks RFB on 5901 directly) or "web" (noVNC page via websockify on 6080).
# Anything but an explicit "native" keeps the old behaviour.
VIEWER="web"
if [ -f /root/.pocket-viewer ] && [ "$(tr -d ' \r\n' < /root/.pocket-viewer)" = "native" ]; then VIEWER="native"; fi

# "Fast cores only" (the app writes 1 or 0 to /root/.pocket-fastcores): the virtual screen and VS Code stay on the tablet's
# big CPU cores instead of being moved to the slow little ones between key presses (less lag, a bit more battery). The big
# cores are the ones whose maximum frequency is within 10 % of the highest; if that cannot be told, or fewer than two or all
# of them qualify, or taskset does not work here, nothing is pinned and everything runs as before.
PIN=()
if [ -f /root/.pocket-fastcores ] && [ "$(tr -d ' \r\n' < /root/.pocket-fastcores)" = "1" ] && command -v taskset >/dev/null 2>&1; then
    BIG_CPUS=""
    MAXF=0
    for f in /sys/devices/system/cpu/cpu[0-9]*/cpufreq/cpuinfo_max_freq; do
        [ -r "$f" ] || continue
        v=$(cat "$f" 2>/dev/null) || continue
        case "$v" in ''|*[!0-9]*) continue ;; esac
        [ "$v" -gt "$MAXF" ] && MAXF=$v
    done
    if [ "$MAXF" -gt 0 ]; then
        TOTAL=0
        BIG=0
        for f in /sys/devices/system/cpu/cpu[0-9]*/cpufreq/cpuinfo_max_freq; do
            [ -r "$f" ] || continue
            v=$(cat "$f" 2>/dev/null) || continue
            case "$v" in ''|*[!0-9]*) continue ;; esac
            n="${f#/sys/devices/system/cpu/cpu}"; n="${n%%/*}"
            TOTAL=$((TOTAL + 1))
            if [ $((v * 100)) -ge $((MAXF * 90)) ]; then BIG_CPUS="${BIG_CPUS:+$BIG_CPUS,}$n"; BIG=$((BIG + 1)); fi
        done
        if [ "$BIG" -ge 2 ] && [ "$BIG" -lt "$TOTAL" ] && taskset -c "$BIG_CPUS" true 2>/dev/null; then
            PIN=(taskset -c "$BIG_CPUS")
            echo "[start-vscode] Fast cores only: CPUs $BIG_CPUS of $TOTAL."
        fi
    fi
fi

start_xserver() {
    pkill -9 -f Xtigervnc 2>/dev/null
    rm -rf /tmp/.X1-lock /tmp/.X11-unix/X1
    echo "[start-vscode] Starting Embedded X11 Server (Xtigervnc :1, $GEOMETRY)..."
    # Option sets, best first (the next one is only tried when the server did not come up):
    #  - MIT-SHM is switched off on purpose: it needs SysV shared memory, which Android blocks, and clients such as
    #    Electron then die with "XIO: fatal IO error 38 (Function not implemented)". Without it they send their
    #    images through the socket instead.
    #  - SendPrimary=0: only a real copy (Ctrl+C / Copy) is sent to the Android clipboard, not every text selection
    #    (X's "primary selection"), so merely selecting text in VS Code no longer overwrites what was copied in Android.
    local OPTS
    #  - FrameRate=120: the server may push up to 120 pictures a second (default 60: a change could wait up to 16 ms for the
    #    next slot, now at most about 8 ms), more than a 90 Hz tablet screen can show.
    for OPTS in "-extension MIT-SHM -SendPrimary=0 -FrameRate=120" "-extension MIT-SHM -SendPrimary=0" "-extension MIT-SHM" ""; do
        rm -rf /tmp/.X1-lock /tmp/.X11-unix/X1
        # shellcheck disable=SC2086  (the option set is meant to be split into words)
        "${PIN[@]}" Xtigervnc :1 \
            -geometry "$GEOMETRY" \
            -depth 24 \
            -localhost \
            -SecurityTypes None \
            -AlwaysShared \
            $OPTS &
        sleep 2
        if pgrep -x Xtigervnc >/dev/null 2>&1; then
            return 0
        fi
        echo "[start-vscode] Xtigervnc did not start with options '${OPTS:-defaults}' - trying the next set."
    done
    return 1
}

# Keeps the virtual screen, the window manager (and, for the web viewer, the noVNC bridge) alive: if VS Code crashes,
# the screen in the app must not go away (that was the "Web page not available / ERR_CONNECTION_REFUSED" screen).
ensure_display() {
    if ! pgrep -x Xtigervnc >/dev/null 2>&1; then
        echo "[start-vscode] X server is not running - restarting it."
        start_xserver
    fi
    if ! pgrep -x openbox >/dev/null 2>&1; then
        openbox &
    fi
    if [ "$VIEWER" != "native" ] && ! pgrep -f "websockify" >/dev/null 2>&1; then
        websockify --web=/usr/share/novnc/ 127.0.0.1:6080 127.0.0.1:5901 &
        sleep 1
    fi
}

export DISPLAY=:1
start_xserver
openbox &
if [ "$VIEWER" != "native" ]; then
    websockify --web=/usr/share/novnc/ 127.0.0.1:6080 127.0.0.1:5901 &
fi

# The app opens the screen as soon as it sees VSCODE_DISPLAY_READY. Say it only when the X server (5901) and, for the
# web viewer, the noVNC web bridge (6080) really accept connections; a fixed "sleep 1" was sometimes too short on the
# first launch and the app showed "Web page not available" until the next start.
wait_port() {
    local host="$1" port="$2" tries="$3" i=0
    while [ "$i" -lt "$tries" ]; do
        if (exec 3<>"/dev/tcp/$host/$port") 2>/dev/null; then return 0; fi
        sleep 0.5
        i=$((i + 1))
    done
    return 1
}
wait_port 127.0.0.1 5901 40 || echo "[start-vscode] X server (port 5901) is not answering yet."
if [ "$VIEWER" != "native" ]; then
    wait_port 127.0.0.1 6080 40 || echo "[start-vscode] noVNC (port 6080) is not answering yet."
fi

echo "VSCODE_DISPLAY_READY"

# If VS Code crashes (exit code != 0) it is started again and reopens the previous windows; closing it normally
# (exit code 0) ends the session. Ten quick crashes in a row stop the loop.
RESTARTS=0
RC=0
while true; do
    ensure_display
    STARTED=$(date +%s)
    "${PIN[@]}" dbus-run-session -- /opt/vscode-arm64/bin/code \
        --no-sandbox \
        --disable-smooth-scrolling \
        --disable-gpu \
        --disable-gpu-sandbox \
        --disable-dev-shm-usage \
        --password-store="basic" \
        --user-data-dir="/root/.config/Code" \
        --extensions-dir="/root/.vscode/extensions" \
        --wait \
        "/root/Workspace"
    RC=$?
    echo "[start-vscode] VS Code exited with code $RC (signal $((RC > 128 ? RC - 128 : 0)))"
    if [ "$RC" -eq 0 ]; then break; fi
    if [ $(( $(date +%s) - STARTED )) -ge 120 ]; then RESTARTS=0; fi
    RESTARTS=$((RESTARTS + 1))
    if [ "$RESTARTS" -gt 10 ]; then
        echo "[start-vscode] VS Code keeps crashing - giving up."
        break
    fi
    echo "[start-vscode] VS Code crashed - restarting in 3 s (attempt $RESTARTS of 10)."
    sleep 3
done
exit "$RC"
