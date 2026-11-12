#!/usr/bin/env bash
# ============================================================
# xdo 发版入口 —— 本地只改版本信息，构建/签名/发布全部交给 GitHub Actions
#   用法: ./scripts/release.sh "本次更新说明" [x.y.z]
#   不填版本号时自动 patch +1（0.2.11 -> 0.2.12），versionCode 同时 +1
# 推送后打开 Actions 看进度；Release 与 version.txt 直链由 CI 自动完成。
# ============================================================
set -euo pipefail

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_DIR"

MSG="${1:-}"
NEW_VER="${2:-}"

if [ -z "$MSG" ]; then
    cat <<'USAGE'
用法: ./scripts/release.sh "本次更新说明" [x.y.z]

  ./scripts/release.sh "修复 HLS 无声问题"          # 自动 0.2.11 -> 0.2.12，versionCode+1
  ./scripts/release.sh "新增功能" 0.3.0            # 指定版本号

脚本只做三件事：改 version.txt → 提交 → 打 tag 推送；APK 由 GitHub Actions 构建并发布 Release。
USAGE
    exit 1
fi

CUR_CODE=$(sed -nE 's/.*"versionCode"[[:space:]]*:[[:space:]]*([0-9]+).*/\1/p' version.txt | head -1)
CUR_NAME=$(sed -nE 's/.*"versionName"[[:space:]]*:[[:space:]]*"([^"]+)".*/\1/p' version.txt | head -1)

if [ -z "$CUR_CODE" ] || [ -z "$CUR_NAME" ]; then
    echo "无法解析 version.txt，请检查文件格式" >&2
    exit 1
fi

if [ -z "$NEW_VER" ]; then
    PATCH="${CUR_NAME##*.}"
    BASE="${CUR_NAME%.*}"
    NEW_VER="${BASE}.$((PATCH + 1))"
fi

if ! printf '%s' "$NEW_VER" | grep -qE '^[0-9]+\.[0-9]+\.[0-9]+$'; then
    echo "版本号格式应为 x.y.z，收到：$NEW_VER" >&2
    exit 1
fi

NEW_CODE=$((CUR_CODE + 1))
TAG="v${NEW_VER}"

if git rev-parse -q --verify "refs/tags/$TAG" > /dev/null; then
    echo "tag $TAG 已存在，请换一个版本号" >&2
    exit 1
fi

if [ -n "$(git status --porcelain --untracked-files=no)" ]; then
    echo "工作区有未提交的改动，请先提交或 stash：" >&2
    git status --short --untracked-files=no >&2
    exit 1
fi

# 转义成 JSON 字符串（反斜杠 / 双引号 / 换行），纯 bash 实现，避免 sed 转义踩坑
MSG_ESC=${MSG//\\/\\\\}
MSG_ESC=${MSG_ESC//\"/\\\"}
MSG_ESC=${MSG_ESC//$'\r'/}
MSG_ESC=${MSG_ESC//$'\n'/\\n}
REMOTE_URL=$(git remote get-url origin)
REPO_SLUG=$(printf '%s' "$REMOTE_URL" | sed -E 's#.*[:/]([^/]+/[^/]+?)(\.git)?$#\1#')

cat > version.txt <<EOF
{
  "versionCode": ${NEW_CODE},
  "versionName": "${NEW_VER}",
  "updateMessage": "${MSG_ESC}",
  "apkUrl": "https://github.com/${REPO_SLUG}/releases/download/${TAG}/xdo_v${NEW_VER}.apk"
}
EOF

echo "=== 新版本信息 ==="
cat version.txt
echo

git add version.txt
git commit -m "release: ${TAG}" -m "$MSG"
git tag -a "$TAG" -m "xdo ${TAG}"

echo "推送 main 与 tag ${TAG} ..."
git push origin main
git push origin "$TAG"

cat <<EOF

已触发 GitHub Actions 发版：${TAG}（versionCode ${NEW_CODE}）
  构建进度: https://github.com/${REPO_SLUG}/actions
  发布结果: https://github.com/${REPO_SLUG}/releases/tag/${TAG}
EOF
