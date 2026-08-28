#!/usr/bin/env bash
# =============================================================================
# prepare-assets.sh — 把仓库根目录的桌面端素材预处理成 Android APK 内嵌素材。
#
# 为什么需要这一步：
#   Android 的 MediaCodec/ExoPlayer 无法解码 VP9 的 alpha 通道（透明视频），
#   直接打包原始 .webm 会在手机上渲染成黑底。这里在构建机（GitHub Actions）
#   用 ffmpeg 把透明 WebM 重编码为 "旁路 alpha" h264：
#       左半 = 原 RGB 画面（不透明），右半 = 灰度 alpha 通道
#   运行期由 App 内的 OpenGL shader 取左半 RGB + 右半灰度重新合成透明像素。
#   实测体积反而比原 WebM 更小（约 0.5x）。手机上不需要任何 ffmpeg。
#
# 输出目录：android/app/src/main/assets/pet/
#   pet/videos/<folder>/<中文名>.mp4    旁路 alpha 动画（结构与原 videos/ 一致）
#   pet/manifest.json                  动画名 -> {file, duration} 时长清单
#   pet/text_clips.json                含文字动画（转向时不镜像）
#   pet/sounds/click.wav               点击音效
#   pet/chat/*.jpg                     聊天窗口背景
#   pet/easter/*.*                     彩蛋弹窗图片池
#   pet/thumbs/<folder>/<名>.webp      动画缩略图（设置页动画集用）
#
# 用法：bash android/scripts/prepare-assets.sh [--force]
# =============================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
OUT="$ROOT/android/app/src/main/assets/pet"
SRC_CHARS="$ROOT/assets/characters"
CRF="${PET_CRF:-28}"            # 视频质量，越大越小（体积已经比原 webm 小，28 质量充裕）
PRESET="${PET_PRESET:-medium}"
JOBS="${PET_JOBS:-$(nproc 2>/dev/null || echo 4)}"

command -v ffmpeg >/dev/null || { echo "错误：需要 ffmpeg（构建机一次性工具，不进 APK）"; exit 1; }
command -v ffprobe >/dev/null || { echo "错误：需要 ffprobe"; exit 1; }

rm -rf "$OUT"
mkdir -p "$OUT/videos" "$OUT/sounds" "$OUT/chat" "$OUT/easter" "$OUT/thumbs"

echo "== 素材预处理 → $OUT =="

# ---------- 1. 动画：透明 WebM → 旁路 alpha h264 ----------
mapfile -d '' WEBMS < <(find "$SRC_CHARS" -name '*.webm' -print0)
TOTAL=${#WEBMS[@]}
echo "  动画文件: $TOTAL 个，并行 $JOBS 线程，CRF=$CRF"

enc_one() {
  local src="$1" dst="$2"
  ffmpeg -y -v error -hide_banner -c:v libvpx-vp9 -i "$src" \
    -filter_complex "[0:v]split=2[rgb][al];[rgb]format=rgba,colorchannelmixer=aa=0[rgb];[al]extractplanes=a,format=gray[al];[rgb][al]hstack=2[out]" \
    -map "[out]" -c:v libx264 -crf "$CRF" -preset "$PRESET" -tune animation \
    -pix_fmt yuv420p -movflags +faststart -an "$dst"
}
export -f enc_one
export CRF PRESET

idx=0
for src in "${WEBMS[@]}"; do
  rel="${src#"$SRC_CHARS"/}"                       # shenshen/videos/idle/待机呼吸休闲.webm
  [ -z "${rel##*videos/*}" ] || continue
  rel="${rel#*/videos/}"                           # idle/待机呼吸休闲.webm
  folder="$(dirname "$rel")"
  name="$(basename "$rel" .webm)"
  mkdir -p "$OUT/videos/$folder"
  echo "    $name" >> "$OUT/.progress"
  (enc_one "$src" "$OUT/videos/$folder/$name.mp4" 2>>"$OUT/.errors") &
  idx=$((idx+1))
  if [ $((idx % JOBS)) -eq 0 ]; then wait; fi
done
wait
rm -f "$OUT/.progress" "$OUT/.errors"

COUNT=$(find "$OUT/videos" -name '*.mp4' | wc -l)
[ "$COUNT" -eq "$TOTAL" ] || { echo "错误：编码产出 $COUNT/$TOTAL 不完整"; exit 1; }

# ---------- 2. 时长 manifest ----------
python3 - "$OUT" <<'PY'
import json, os, subprocess, sys
out = sys.argv[1]
manifest = {}
for root, dirs, files in os.walk(os.path.join(out, 'videos')):
    for f in files:
        if not f.endswith('.mp4'):
            continue
        p = os.path.join(root, f)
        rel = os.path.relpath(p, os.path.join(out, 'videos'))
        d = subprocess.run(['ffprobe', '-v', 'error', '-show_entries',
                            'format=duration', '-of', 'default=nw=1:nk=1', p],
                           capture_output=True, text=True).stdout.strip()
        try:
            dur = round(float(d), 3)
        except ValueError:
            dur = 0.0
        manifest[os.path.splitext(f)[0]] = {
            'file': rel.replace(os.sep, '/'),
            'duration': dur,
        }
with open(os.path.join(out, 'manifest.json'), 'w', encoding='utf-8') as fp:
    json.dump(manifest, fp, ensure_ascii=False, indent=1)
print(f"  manifest.json: {len(manifest)} 段动画")
PY

# ---------- 3. 其他素材 ----------
cp -f "$SRC_CHARS/shenshen/videos/text_clips.json" "$OUT/text_clips.json" 2>/dev/null || true
cp -f "$ROOT/assets/sounds/"* "$OUT/sounds/" 2>/dev/null || true
cp -f "$ROOT/assets/chat/"* "$OUT/chat/" 2>/dev/null || true
cp -f "$ROOT/assets/big_blue_fat_fish/"* "$OUT/easter/" 2>/dev/null || true

# ---------- 4. 缩略图（设置页动画集） ----------
thumb_one() {
  local src="$1" dst="$2"
  ffmpeg -y -v error -hide_banner -c:v libvpx-vp9 -i "$src" -frames:v 1 -vf "scale=220:-1" \
    -c:v libwebp -lossless 0 -q:v 70 "$dst"
}
export -f thumb_one
idx=0
for src in "${WEBMS[@]}"; do
  rel="${src#"$SRC_CHARS"/}"; [ -z "${rel##*videos/*}" ] || continue
  rel="${rel#*/videos/}"
  folder="$(dirname "$rel")"; name="$(basename "$rel" .webm)"
  mkdir -p "$OUT/thumbs/$folder"
  (thumb_one "$src" "$OUT/thumbs/$folder/$name.webp" 2>/dev/null) &
  idx=$((idx+1))
  if [ $((idx % JOBS)) -eq 0 ]; then wait; fi
done
wait

echo "== 完成：$(du -sh "$OUT" | cut -f1) =="
