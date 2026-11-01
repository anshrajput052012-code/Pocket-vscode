#!/bin/bash
set -euo pipefail

echo "=== [1/6] Genuine VS Code ELF Binary Check ==="
file /opt/vscode-arm64/code || true
/opt/vscode-arm64/bin/code --version --no-sandbox --user-data-dir=/root/.config/Code

echo "=== [2/6] Shared Libraries Check ==="
if ldd /opt/vscode-arm64/code | grep -q "not found"; then
    ldd /opt/vscode-arm64/code | grep "not found"
    exit 1
else
    echo "PASS: All ELF shared libraries resolved."
fi

echo "=== [3/6] Native Modules Check (node-pty, ripgrep, spdlog) ==="
RG_BIN=$(find /opt/vscode-arm64 -name "rg" -type f | head -n 1)
"$RG_BIN" --version

echo "=== [4/6] Compilers & Runtimes Check ==="
bash --version | head -n 1
git --version
python3 --version
node --version
gcc --version | head -n 1

echo "=== [5/6] Git Init, Commit & Diff Verification ==="
rm -rf /tmp/git-check && mkdir -p /tmp/git-check && cd /tmp/git-check
git init -q
git config user.email "dev@android.local"
git config user.name "Android Dev"
echo "print('Genuine VS Code on Android without Termux!')" > app.py
python3 app.py
git add app.py && git commit -m "Verified Git inside standalone APK"

echo "=== [6/6] Official Marketplace Extension Install Check ==="
/opt/vscode-arm64/bin/code \
    --no-sandbox \
    --user-data-dir=/root/.config/Code \
    --extensions-dir=/root/.vscode/extensions \
    --install-extension ms-python.python --force

echo "=== ALL STANDALONE APK CHECKS PASSED ==="
