#!/usr/bin/env bash
# build image ทั้งหมดแล้ว import เข้า K3s (containerd) บน VM โดยไม่ต้องมี registry
#   ./devops/k8s/build-images.sh               # build + import (ต้องใช้ docker ได้ + รหัส sudo ตอน import)
#   NO_IMPORT=1 ./devops/k8s/build-images.sh   # build อย่างเดียว (เช่นให้ Claude build แล้วคนรัน import เอง)
#   IMPORT_ONLY=1 ./devops/k8s/build-images.sh # import อย่างเดียว ไม่ build ใหม่ (เช่นใส่รหัส sudo ผิด)
# K3s ไม่ได้ใช้ image ร่วมกับ docker — ไม่ import = pod ขึ้น ErrImageNeverPull
set -euo pipefail
cd "$(dirname "$0")/../.."

IMAGES=(bangmod-backend:latest bangmod-ai-service:latest bangmod-frontend:prod bangmod-frontend:uat)

if ! docker info >/dev/null 2>&1; then
  cat >&2 <<'EOF'
✗ ใช้ docker ไม่ได้ (permission denied ที่ /var/run/docker.sock หรือ docker ไม่ได้รัน)
  แก้อย่างใดอย่างหนึ่ง:
    - sudo usermod -aG docker "$USER"  แล้ว logout / SSH เข้าใหม่  (group docker = สิทธิ์เท่า root)
    - หรือรันสคริปต์นี้ด้วย sudo ครั้งเดียว:  sudo ./devops/k8s/build-images.sh
EOF
  exit 1
fi

if [ -z "${IMPORT_ONLY:-}" ]; then
  docker build -t bangmod-backend:latest backend/
  docker build -t bangmod-ai-service:latest ai-service/
  docker build -t bangmod-frontend:prod frontend/
  docker build --build-arg MODE=uat --build-arg APP_BASE=/uat/ -t bangmod-frontend:uat frontend/
fi

if [ -n "${NO_IMPORT:-}" ]; then
  echo "✓ build เสร็จ (ยังไม่ได้ import) — รันต่อ:  IMPORT_ONLY=1 ./devops/k8s/build-images.sh"
  exit 0
fi

# บันทึกเป็นไฟล์ก่อน แล้วค่อย import (เห็น error ชัดกว่า pipe และลบเองเมื่อจบ)
TAR="$(mktemp /tmp/bangmod-images.XXXXXX.tar)"
trap 'rm -f "$TAR"' EXIT
docker save -o "$TAR" "${IMAGES[@]}"
echo "▸ import เข้า K3s (ใส่รหัสผ่านของ user นี้ · พิมพ์ด้วยแป้นภาษาอังกฤษ · จอจะไม่แสดงตัวอักษร)"
sudo k3s ctr -n k8s.io images import "$TAR"

# ตรวจว่าเข้า K3s ครบทุกตัว
LISTED="$(sudo k3s ctr -n k8s.io images ls -q)"
MISSING=0
for image in "${IMAGES[@]}"; do
  if ! grep -q "/${image}$" <<<"$LISTED"; then
    echo "✗ ไม่พบ ${image} ใน K3s" >&2
    MISSING=1
  fi
done
[ "$MISSING" -eq 0 ] || exit 1
echo "✓ import ครบ ${#IMAGES[@]} image แล้ว — ต่อด้วย: kubectl -n <ns> rollout restart deployment/backend deployment/frontend"
