#!/usr/bin/env bash
# ============================================================
# 把本地发版签名密钥写入 GitHub Actions Secrets（只需跑一次）
#   用法: ./scripts/setup-ci-secrets.sh
# 依赖: GitHub CLI（gh），首次运行会引导 gh auth login
# 注意: 写 secret 需要仓库 admin 权限；密钥不入库，只保存在 GitHub Secrets 里
# ============================================================
set -euo pipefail

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_DIR"

KS="${XDO_KEYSTORE:-$HOME/.android/xdo-release.keystore}"
STORE_PASS="${XDO_STORE_PASSWORD:-android}"
KEY_ALIAS="${XDO_KEY_ALIAS:-androiddebugkey}"
KEY_PASS="${XDO_KEY_PASSWORD:-android}"

if [ ! -f "$KS" ]; then
    echo "找不到签名密钥：$KS" >&2
    echo "可用 XDO_KEYSTORE=/path/to/xxx.keystore 指定" >&2
    exit 1
fi

if ! command -v gh > /dev/null; then
    echo "未安装 GitHub CLI（gh），请先安装：https://cli.github.com/" >&2
    exit 1
fi

if ! gh auth status > /dev/null 2>&1; then
    echo "GitHub CLI 未登录，开始登录流程 ..."
    gh auth login
fi

REPO_SLUG=$(gh repo view --json nameWithOwner -q .nameWithOwner)

echo "=== 目标仓库: $REPO_SLUG ==="
echo "=== 签名密钥: $KS ==="

B64=$(base64 -w0 "$KS" 2>/dev/null || base64 "$KS" | tr -d '\n')
printf '%s' "$B64" | gh secret set XDO_KEYSTORE_BASE64 --repo "$REPO_SLUG"
printf '%s' "$STORE_PASS" | gh secret set XDO_STORE_PASSWORD --repo "$REPO_SLUG"
printf '%s' "$KEY_ALIAS" | gh secret set XDO_KEY_ALIAS --repo "$REPO_SLUG"
printf '%s' "$KEY_PASS" | gh secret set XDO_KEY_PASSWORD --repo "$REPO_SLUG"

echo
echo "=== 已写入的 Secrets ==="
gh secret list --repo "$REPO_SLUG"

cat <<'EOF'

下一步：
  1) 确认 Actions 已启用（仓库 Settings → Actions → General → Allow all actions）
  2) 发版：./scripts/release.sh "更新说明"
  3) 若仓库开了分支保护导致 CI 无法回写 version.txt，需要给 Actions 放开 main 的推送权限，
     或发版后手动核对 version.txt 的 apkUrl
EOF
