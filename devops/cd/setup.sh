#!/usr/bin/env bash
# =====================================================================
# ติดตั้ง CD บน VM (ครั้งเดียว · รันด้วย user ที่ deploy เช่น adminstudent · ไม่ต้อง sudo)
#   ./devops/cd/setup.sh
# ทำให้: โฟลเดอร์ $CD_HOME, clone repo แยก, registry ในเครื่อง (docker), สำเนา deploy.sh, crontab ทุก 3 นาที
# ขั้นที่ต้องใช้ sudo (ตั้ง registries.yaml ของ K3s) สคริปต์จะพิมพ์คำสั่งให้รันเอง
# ต้องทำก่อน: deploy uat ด้วยมือสำเร็จแล้ว 1 ครั้ง (devops/README.md) · user อยู่ใน group docker · ~/.kube/config
# =====================================================================
set -euo pipefail

CD_HOME="${CD_HOME:-$HOME/bangmod-cd}"
REPO_URL="${REPO_URL:-https://github.com/Thammasornn/Capstone.git}"
SRC="$(cd "$(dirname "$0")/../.." && pwd)"          # repo ที่รันสคริปต์นี้ (เช่น ~/Capstone)
REGISTRY_IMAGE="registry:2.8.3"

ok()   { printf '\033[32m✓ %s\033[0m\n' "$*"; }
todo() { printf '\033[33m! %s\033[0m\n' "$*"; }
die()  { printf '\033[31m✗ %s\033[0m\n' "$*" >&2; exit 1; }

for cmd in git docker kubectl curl flock crontab; do
  command -v "$cmd" >/dev/null || die "ไม่พบคำสั่ง $cmd"
done
docker info >/dev/null 2>&1 || die "ใช้ docker ไม่ได้ — เพิ่ม user เข้า group docker แล้ว login ใหม่ (devops/README.md › เตรียม VM)"
kubectl get ns uat >/dev/null 2>&1 || die "kubectl ใช้ไม่ได้ หรือยังไม่มี namespace uat — deploy uat ด้วยมือก่อน"

mkdir -p "$CD_HOME"/{bin,secrets,state,logs,registry,work}
chmod 700 "$CD_HOME/secrets"

# 1) clone แยกสำหรับ CD (ไม่ยุ่งกับ repo ที่คนใช้ทำงาน)
if [ ! -d "$CD_HOME/repo/.git" ]; then
  git clone --quiet "$REPO_URL" "$CD_HOME/repo"
fi
git -C "$CD_HOME/repo" fetch --quiet origin
ok "repo สำหรับ CD: $CD_HOME/repo"

# 2) registry ในเครื่อง (ฟังเฉพาะ 127.0.0.1 — เครื่องอื่นเข้าไม่ได้)
if ! docker ps --format '{{.Names}}' | grep -qx bangmod-registry; then
  docker rm -f bangmod-registry >/dev/null 2>&1 || true
  docker run -d --name bangmod-registry --restart=always \
    -p 127.0.0.1:5000:5000 -v "$CD_HOME/registry:/var/lib/registry" "$REGISTRY_IMAGE" >/dev/null
fi
for _ in $(seq 1 20); do curl -fsS http://localhost:5000/v2/ >/dev/null 2>&1 && break; sleep 1; done
curl -fsS http://localhost:5000/v2/ >/dev/null || die "registry ไม่ตอบที่ localhost:5000"
ok "registry: localhost:5000 (ข้อมูลที่ $CD_HOME/registry)"

# 3) K3s ต้องรู้จัก registry นี้ (ต้องใช้ sudo — คนรันเอง)
if ! grep -qs 'localhost:5000' /etc/rancher/k3s/registries.yaml; then
  todo "ยังไม่ได้ตั้ง registry ให้ K3s — รันเอง (ต้องใช้รหัส sudo · k3s restart ไม่ทำให้ pod ที่รันอยู่หยุด):"
  echo "    sudo cp $SRC/devops/cd/registries.yaml /etc/rancher/k3s/registries.yaml && sudo systemctl restart k3s"
else
  ok "K3s รู้จัก registry แล้ว (/etc/rancher/k3s/registries.yaml)"
fi

# 4) ความลับ: ใช้ชุดเดียวกับที่ deploy uat ด้วยมือ (FACE_EMBEDDING_KEY ต้องไม่เปลี่ยน)
if [ ! -f "$CD_HOME/secrets/uat.env" ]; then
  if [ -f "$SRC/devops/k8s/overlays/uat/secret.env" ]; then
    cp "$SRC/devops/k8s/overlays/uat/secret.env" "$CD_HOME/secrets/uat.env"
    ok "copy secret ของ uat → $CD_HOME/secrets/uat.env"
  else
    todo "ไม่พบ secret ของ uat — copy secret.env ที่ใช้อยู่ไปไว้ที่ $CD_HOME/secrets/uat.env"
  fi
fi
[ -f "$CD_HOME/secrets/uat.env" ] && chmod 600 "$CD_HOME/secrets/uat.env"
[ -f "$CD_HOME/secrets/prod.env" ] || todo "prod: ตอน deploy prod ครั้งแรกให้วาง secret ของ prod ที่ $CD_HOME/secrets/prod.env (คนละชุดกับ uat)"

# 5) ค่าตั้งต้น (ไม่มีความลับ)
[ -f "$CD_HOME/config.env" ] || cat > "$CD_HOME/config.env" <<'EOF'
# ค่าของ devops/cd/deploy.sh บนเครื่องนี้ (ห้ามใส่ความลับ)
PUBLIC_URL=http://cp26ms3.sit.kmutt.ac.th
REGISTRY=localhost:5000
EOF

# 6) สคริปต์ที่ cron เรียก = deploy.sh ของ origin/Dev (อัปเดตตัวเองหลัง deploy uat สำเร็จ)
git -C "$CD_HOME/repo" show origin/Dev:devops/cd/deploy.sh > "$CD_HOME/bin/deploy.sh"
chmod +x "$CD_HOME/bin/deploy.sh"
ok "สคริปต์ deploy: $CD_HOME/bin/deploy.sh"

# 7) cron ทุก 3 นาที (crontab ของ user นี้ · ไม่ต้อง sudo)
KUBECONFIG_PATH="${KUBECONFIG:-$HOME/.kube/config}"
CRON_LINE="*/3 * * * * PATH=/usr/local/bin:/usr/bin:/bin KUBECONFIG=$KUBECONFIG_PATH CD_HOME=$CD_HOME $CD_HOME/bin/deploy.sh uat --if-changed >> $CD_HOME/logs/cron.log 2>&1"
if crontab -l 2>/dev/null | grep -qF "$CD_HOME/bin/deploy.sh uat"; then
  ok "cron มีอยู่แล้ว"
else
  (crontab -l 2>/dev/null || true; echo "# bangmod CD: Dev → uat"; echo "$CRON_LINE") | crontab -
  ok "ติดตั้ง cron: Dev → uat ทุก 3 นาที"
fi

echo
echo "เสร็จ — ลอง deploy ครั้งแรกด้วยมือเพื่อดูผล:  $CD_HOME/bin/deploy.sh uat"
echo "ดูสถานะ:  $CD_HOME/bin/deploy.sh status   ·   log: $CD_HOME/logs/"
echo "ปิด CD ชั่วคราว:  crontab -e แล้วใส่ # หน้าบรรทัด bangmod CD"
