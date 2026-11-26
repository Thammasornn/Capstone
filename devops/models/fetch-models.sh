#!/usr/bin/env bash
# =====================================================================
# ดาวน์โหลดโมเดลของ AI Service แล้วตรวจ SHA-256 (โมเดลไม่อยู่ใน git)
#   ./devops/models/fetch-models.sh                 # ลง /opt/bangmod/models (ที่ pod ai-service mount)
#   MODELS_DIR=/path ./devops/models/fetch-models.sh
# ต้องเขียนโฟลเดอร์ปลายทางได้ — ถ้ายังไม่มี สคริปต์จะพิมพ์คำสั่ง sudo ให้รันเอง
# รันซ้ำได้: ไฟล์ที่มีอยู่และ hash ตรงจะข้าม · hash ไม่ตรง = ไม่ใช้ไฟล์นั้น (กันไฟล์เสีย / ถูกสลับ)
#
# โมเดล (ตรวจแล้ว 2026-11-22 · รายละเอียดและสัญญาอนุญาตใน devops/README.md › ติดตั้งโมเดล AI):
#   yolov8n-face-pose.pt   derronqi/yolov8-face (GPL-3.0) · YOLOv8n PoseModel · 1 class · 5 จุดบนหน้า
#   face_recognition_sface_2021dec.onnx   OpenCV Zoo (Apache-2.0) · SFace 128 มิติ
#   minifasnet_v2.onnx / minifasnet_v1se.onnx   anti-spoofing (เพิ่ม 2026-11-26) · Silent-Face-Anti-Spoofing
#     (minivision-ai, Apache-2.0) แปลงเป็น ONNX โดย uniface · Hugging Face yakhyo/uniface-weights (pin commit)
#     ไม่มี 2 ไฟล์นี้ ai-service ยังขึ้น แต่ปิด anti-spoofing เอง (log WARN)
# =====================================================================
set -euo pipefail

MODELS_DIR="${MODELS_DIR:-/opt/bangmod/models}"

# ชื่อไฟล์ | URL | SHA-256 | ขนาด (byte)
MODELS=(
  "yolov8n-face-pose.pt|https://drive.usercontent.google.com/download?id=1qcr9DbgsX3ryrz2uU8w4Xm3cOrRywXqb&export=download&confirm=t|d545bf1add5aa736a4febac4f4f9245a6d596cd0fe70d5d57989fe0cb9e626ca|6389512"
  "face_recognition_sface_2021dec.onnx|https://github.com/opencv/opencv_zoo/raw/main/models/face_recognition_sface/face_recognition_sface_2021dec.onnx|0ba9fbfa01b5270c96627c4ef784da859931e02f04419c829e83484087c34e79|38696353"
  "minifasnet_v2.onnx|https://huggingface.co/yakhyo/uniface-weights/resolve/cbe2dcc059a4a4e36b786c30b06975efcc28039b/minifasnet_v2.onnx|b32929adc2d9c34b9486f8c4c7bc97c1b69bc0ea9befefc380e4faae4e463907|1743581"
  "minifasnet_v1se.onnx|https://huggingface.co/yakhyo/uniface-weights/resolve/cbe2dcc059a4a4e36b786c30b06975efcc28039b/minifasnet_v1se.onnx|ebab7f90c7833fbccd46d3a555410e78d969db5438e169b6524be444862b3676|1742335"
)

ok()  { printf '\033[32m✓ %s\033[0m\n' "$*"; }
die() { printf '\033[31m✗ %s\033[0m\n' "$*" >&2; exit 1; }

sha256() {
  if command -v sha256sum >/dev/null; then sha256sum "$1" | cut -d' ' -f1; else shasum -a 256 "$1" | cut -d' ' -f1; fi
}

if [ ! -d "$MODELS_DIR" ] || [ ! -w "$MODELS_DIR" ]; then
  die "เขียน $MODELS_DIR ไม่ได้ — รันเองก่อน (ต้องใช้รหัส sudo):
    sudo mkdir -p $MODELS_DIR && sudo chown \$USER $MODELS_DIR"
fi
command -v curl >/dev/null || die "ไม่พบ curl"

FAILED=0
for entry in "${MODELS[@]}"; do
  IFS='|' read -r name url want size <<<"$entry"
  dest="$MODELS_DIR/$name"
  if [ -f "$dest" ] && [ "$(sha256 "$dest")" = "$want" ]; then
    ok "$name มีอยู่แล้ว (hash ตรง)"
    continue
  fi
  tmp="$(mktemp "$MODELS_DIR/.$name.XXXXXX")"
  printf '▸ ดาวน์โหลด %s (%s MB)\n' "$name" "$((size / 1048576))"
  if ! curl -fsSL --retry 3 -o "$tmp" "$url"; then
    rm -f "$tmp"; echo "✗ ดาวน์โหลด $name ไม่ได้" >&2; FAILED=1; continue
  fi
  got="$(sha256 "$tmp")"
  if [ "$got" != "$want" ]; then
    rm -f "$tmp"
    echo "✗ $name: SHA-256 ไม่ตรง (ได้ $got) — ไฟล์ต้นทางเปลี่ยน หรือ Google Drive ส่งหน้าเว็บมาแทนไฟล์" >&2
    FAILED=1; continue
  fi
  chmod 644 "$tmp" && mv -f "$tmp" "$dest"
  ok "$name → $dest"
done

[ "$FAILED" -eq 0 ] || die "มีโมเดลที่ยังไม่ได้ — ดาวน์โหลดเองแล้ววางที่ $MODELS_DIR (ดู devops/README.md › ติดตั้งโมเดล AI)"
echo
echo "ต่อ: kubectl -n uat rollout restart deployment/ai-service && kubectl -n uat rollout status deployment/ai-service --timeout=300s"
