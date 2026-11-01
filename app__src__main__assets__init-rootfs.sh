#!/bin/bash
# Pocket VS Code — one-time environment setup (runs INSIDE the PRoot Debian ARM64 rootfs).
# The app parses the "##STAGE|n|label" markers and the apt output to drive the 0-100 % progress bar.
set -euo pipefail
export DEBIAN_FRONTEND=noninteractive

# `_apt` privilege dropping (setgroups) is not permitted under PRoot; run the apt methods as root instead.
APT_OPTS=(-o Acquire::Retries=3 -o APT::Sandbox::User=root)

rm -f /root/.pocket-setup-done

echo "##STAGE|1|Updating package lists"
apt-get "${APT_OPTS[@]}" update -y

echo "##STAGE|2|Installing X11, VS Code libraries and developer tools"
apt-get "${APT_OPTS[@]}" install -y --no-install-recommends \
    ca-certificates curl wget gpg \
    libnss3 libatk1.0-0 libatk-bridge2.0-0 libcups2 libdrm2 \
    libgtk-3-0 libgbm1 libasound2 libxkbfile1 libsecret-1-0 \
    libx11-xcb1 libxcb-dri3-0 libxcomposite1 libxdamage1 libxfixes3 \
    libxrandr2 libxtst6 xdg-utils dbus-x11 x11-xserver-utils \
    tigervnc-standalone-server openbox novnc python3-websockify \
    fonts-noto-core fonts-firacode \
    build-essential gdb git python3 python3-pip python3-venv \
    nodejs npm

echo "##STAGE|3|Writing VS Code configuration"
ln -sf /opt/vscode-arm64/bin/code /usr/local/bin/code

mkdir -p /root/.vscode /root/.config/Code/User

cat > /root/.vscode/argv.json << 'EOF'
{
    "enable-crash-reporter": false,
    "password-store": "basic"
}
EOF

cat > /root/.config/Code/User/settings.json << 'EOF'
{
    "window.titleBarStyle": "custom",
    "terminal.integrated.defaultProfile.linux": "bash",
    "terminal.integrated.gpuAcceleration": "off",
    "security.workspace.trust.enabled": false,
    "files.watcherExclude": {
        "**/.git/objects/**": true,
        "**/node_modules/*/**": true
    }
}
EOF

# Must stay the LAST command: the app treats this file as "setup finished".
touch /root/.pocket-setup-done
echo "##STAGE|4|Done"
echo "[init-rootfs] Embedded Debian ARM64 & Genuine VS Code setup complete."
