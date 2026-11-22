#!/usr/bin/env bash
# =====================================================================
# CD บน VM — ดึงโค้ด → build image → push เข้า registry ในเครื่อง → kubectl apply → ตรวจผล → rollback ถ้าพัง
#
#   deploy.sh uat --if-changed   cron เรียกทุก 3 นาที: deploy เมื่อ origin/Dev มี commit ใหม่เท่านั้น
#   deploy.sh uat                deploy origin/Dev ตอนนี้ (แม้ commit เดิม / เคยพัง)
#   deploy.sh prod [<commit>]    deploy prod จาก origin/main (หรือ commit ที่ระบุ) — ถามยืนยันทุกครั้ง
#   deploy.sh status             ผล deploy ล่าสุดของแต่ละ namespace
#
# cron รันสำเนาที่ $CD_HOME/bin/deploy.sh (อัปเดตตัวเองหลัง deploy uat สำเร็จ) · โค้ดดึงจาก clone แยก $CD_HOME/repo
# ไม่ใช่ repo ที่คนใช้ทำงาน — ติดตั้งด้วย devops/cd/setup.sh
# ไม่แตะฐานข้อมูล: commit ที่แก้ backend/db/ จะไม่ถูก deploy อัตโนมัติ (ต้องรัน schema เองแล้ว deploy ใหม่)
# ห้ามใส่ค่าความลับในไฟล์นี้ — secret.env อยู่ที่ $CD_HOME/secrets/<env>.env
# =====================================================================
set -Eeuo pipefail

CD_HOME="${CD_HOME:-$HOME/bangmod-cd}"
REPO="$CD_HOME/repo"
REGISTRY="${REGISTRY:-localhost:5000}"
[ -f "$CD_HOME/config.env" ] && . "$CD_HOME/config.env"     # PUBLIC_URL, REGISTRY (ไม่มีความลับ)
PUBLIC_URL="${PUBLIC_URL:-http://cp26ms3.sit.kmutt.ac.th}"
STATE="$CD_HOME/state"
LOGS="$CD_HOME/logs"
mkdir -p "$STATE" "$LOGS"
# cron.log (ข้อความก่อนเริ่ม log ของแต่ละรอบ) ไม่ให้โตเกิน 1MB
[ -f "$LOGS/cron.log" ] && [ "$(wc -c < "$LOGS/cron.log")" -gt 1048576 ] && : > "$LOGS/cron.log"

ts()   { date '+%Y-%m-%d %H:%M:%S'; }
log()  { echo "[$(ts)] $*"; }
fail() { log "✗ $*"; exit 1; }

ENV_NAME="${1:-}"
case "$ENV_NAME" in
  uat)    BRANCH=Dev ;;
  prod)   BRANCH=main ;;
  status)
    for e in uat prod; do
      [ -f "$STATE/$e.last" ] && echo "$e: $(cat "$STATE/$e.last")" || echo "$e: ยังไม่เคย deploy ผ่าน CD"
    done
    echo "log ล่าสุด: $(ls -t "$LOGS" 2>/dev/null | head -1)"
    exit 0 ;;
  *) sed -n '3,14p' "$0" | sed 's/^# \{0,1\}//'; exit 1 ;;
esac
shift
IF_CHANGED=0; REF=""
for arg in "$@"; do
  case "$arg" in
    --if-changed) IF_CHANGED=1 ;;
    -*) fail "ไม่รู้จัก option $arg" ;;
    *) REF="$arg" ;;
  esac
done

# รันได้ครั้งละ 1 (cron กับคนสั่งเองไม่ชนกัน)
exec 9>"$STATE/deploy.lock"
if ! flock -n 9; then
  [ "$IF_CHANGED" = 1 ] && exit 0
  fail "มี deploy อื่นกำลังทำงานอยู่ (lock: $STATE/deploy.lock)"
fi

# prod ต้องมีคนสั่งจาก terminal เสมอ (กฎ CLAUDE.md: prod ต้องมีคนยืนยัน) — ห้าม cron
if [ "$ENV_NAME" = prod ] && [ ! -t 0 ]; then
  fail "prod ต้องสั่งเองจาก terminal (ห้าม cron / --if-changed)"
fi
[ "$ENV_NAME" = prod ] && [ "$IF_CHANGED" = 1 ] && fail "prod ไม่รองรับ --if-changed (ต้องสั่งเองทุกครั้ง)"

# ---------- เลือก commit ----------
git -C "$REPO" fetch --quiet origin "$BRANCH"
TARGET="$(git -C "$REPO" rev-parse "${REF:-origin/$BRANCH}^{commit}")"
SHA="${TARGET:0:12}"
LAST_OK="$(cat "$STATE/$ENV_NAME.deployed" 2>/dev/null || true)"
LAST_TRIED="$(cat "$STATE/$ENV_NAME.tried" 2>/dev/null || true)"

if [ "$IF_CHANGED" = 1 ]; then
  # ไม่มี commit ใหม่ หรือ commit นี้เคยลองแล้ว (สำเร็จ / พัง / ถูกกัน) → เงียบ ไม่ทำซ้ำทุก 3 นาที
  [ "$TARGET" = "$LAST_OK" ] && exit 0
  [ "$TARGET" = "$LAST_TRIED" ] && exit 0
fi

# ตั้งแต่ตรงนี้เขียน log ทุกบรรทัด
LOG_FILE="$LOGS/$(date '+%Y%m%d-%H%M%S')-$ENV_NAME-$SHA.log"
exec > >(tee -a "$LOG_FILE") 2>&1
echo "$TARGET" > "$STATE/$ENV_NAME.tried"
trap 'finish failed "คำสั่งล้มเหลว (บรรทัด $LINENO) — ดู log"' ERR
log "▸ deploy $ENV_NAME ← $BRANCH @ $SHA ($(git -C "$REPO" log -1 --format='%an: %s' "$TARGET"))"

finish() {   # finish <ok|failed|blocked> <ข้อความ>
  echo "$1 · $SHA · $(ts) · $2 · log: $LOG_FILE" > "$STATE/$ENV_NAME.last"
  # เก็บ log ล่าสุด 100 ไฟล์
  ls -t "$LOGS"/*.log 2>/dev/null | tail -n +101 | xargs -r rm -f
}

if [ "$ENV_NAME" = prod ]; then
  printf 'deploy prod เป็น %s ? พิมพ์ prod เพื่อยืนยัน: ' "$SHA"
  read -r answer
  [ "$answer" = prod ] || { finish blocked "ผู้ใช้ยกเลิก"; fail "ยกเลิก"; }
fi

# ---------- ตรวจก่อน ----------
docker info >/dev/null 2>&1 || { finish failed "ใช้ docker ไม่ได้"; fail "ใช้ docker ไม่ได้ (group docker / docker ไม่ได้รัน)"; }
kubectl get ns "$ENV_NAME" >/dev/null 2>&1 || { finish failed "ไม่มี namespace $ENV_NAME"; fail "ไม่มี namespace $ENV_NAME — deploy ครั้งแรกตาม devops/README.md ก่อน"; }
curl -fsS "http://$REGISTRY/v2/" >/dev/null || { finish failed "registry ไม่ตอบ"; fail "registry $REGISTRY ไม่ตอบ — ดู devops/cd/setup.sh"; }
SECRETS="$CD_HOME/secrets/$ENV_NAME.env"
[ -f "$SECRETS" ] || { finish failed "ไม่มี $SECRETS"; fail "ไม่มี $SECRETS"; }

# commit ที่แก้ schema / ข้อมูล → ต้องมีคนตัดสิน (DB ย้อนกลับไม่ได้)
if [ -n "$LAST_OK" ] && ! git -C "$REPO" diff --quiet "$LAST_OK" "$TARGET" -- backend/db/; then
  if [ "${ALLOW_DB_CHANGE:-}" != 1 ]; then
    git -C "$REPO" diff --stat "$LAST_OK" "$TARGET" -- backend/db/
    finish blocked "commit แก้ backend/db/ — รัน schema เองแล้วสั่ง ALLOW_DB_CHANGE=1 deploy.sh $ENV_NAME"
    fail "หยุด: commit นี้แก้ backend/db/ (schema / ข้อมูล) ต้องอัปเดต DB เองก่อน แล้วรัน ALLOW_DB_CHANGE=1 $0 $ENV_NAME"
  fi
  log "! ALLOW_DB_CHANGE=1 — deploy ทั้งที่ backend/db/ เปลี่ยน (ผู้ใช้ยืนยันว่าอัปเดต DB แล้ว)"
fi

# ---------- build + push ----------
WORK="$CD_HOME/work/$ENV_NAME"
rm -rf "$WORK"
git -C "$REPO" worktree prune
git -C "$REPO" worktree add --quiet --detach "$WORK" "$TARGET"
cleanup() { git -C "$REPO" worktree remove --force "$WORK" >/dev/null 2>&1 || true; }
trap cleanup EXIT

if [ "$ENV_NAME" = uat ]; then FE_ARGS=(--build-arg MODE=uat --build-arg APP_BASE=/uat/); else FE_ARGS=(); fi
BACKEND="$REGISTRY/bangmod-backend:$SHA"
AI="$REGISTRY/bangmod-ai-service:$SHA"
FRONTEND="$REGISTRY/bangmod-frontend:$ENV_NAME-$SHA"

log "▸ build image"
docker build -q -t "$BACKEND" "$WORK/backend"
docker build -q -t "$AI" "$WORK/ai-service"
docker build -q "${FE_ARGS[@]}" -t "$FRONTEND" "$WORK/frontend"
log "▸ push เข้า $REGISTRY"
for image in "$BACKEND" "$AI" "$FRONTEND"; do docker push -q "$image" >/dev/null; done

# ---------- apply ----------
cp "$SECRETS" "$WORK/devops/k8s/overlays/$ENV_NAME/secret.env"
chmod 600 "$WORK/devops/k8s/overlays/$ENV_NAME/secret.env"
GEN="$WORK/devops/k8s/.generated/$ENV_NAME"
mkdir -p "$GEN"
cat > "$GEN/kustomization.yaml" <<EOF
# สร้างโดย devops/cd/deploy.sh — ใช้ image จาก registry (tag = commit) แทน image ที่ import เอง
apiVersion: kustomize.config.k8s.io/v1beta1
kind: Kustomization
resources:
  - ../../overlays/$ENV_NAME
images:
  - { name: bangmod-backend,    newName: $REGISTRY/bangmod-backend,    newTag: "$SHA" }
  - { name: bangmod-ai-service, newName: $REGISTRY/bangmod-ai-service, newTag: "$SHA" }
  - { name: bangmod-frontend,   newName: $REGISTRY/bangmod-frontend,   newTag: "$ENV_NAME-$SHA" }
patches:
EOF
for d in backend frontend ai-service; do
  cat >> "$GEN/kustomization.yaml" <<EOF
  - target: { kind: Deployment, name: $d }
    patch: |-
      - { op: replace, path: /spec/template/spec/containers/0/imagePullPolicy, value: IfNotPresent }
EOF
done

log "▸ kubectl apply"
kubectl apply -k "$GEN"
rm -f "$WORK/devops/k8s/overlays/$ENV_NAME/secret.env"

rollback() {
  log "✗ $1 → rollback backend / frontend เป็นเวอร์ชันก่อนหน้า"
  kubectl -n "$ENV_NAME" rollout undo deployment/backend  || true
  kubectl -n "$ENV_NAME" rollout undo deployment/frontend || true
  finish failed "$1 (rollback แล้ว)"
  exit 1
}

log "▸ รอ backend / frontend พร้อม"
kubectl -n "$ENV_NAME" rollout status deployment/backend  --timeout=300s || rollback "backend ไม่พร้อมภายใน 5 นาที"
kubectl -n "$ENV_NAME" rollout status deployment/frontend --timeout=180s || rollback "frontend ไม่พร้อมภายใน 3 นาที"
# ai-service ไม่ต้องรอ (ไม่มีโมเดล = start ไม่ขึ้น ซึ่งเป็นเรื่องปกติตอนนี้)

# ---------- ตรวจผล (กฎ CLAUDE.md ข้อ 13–15) ----------
log "▸ ตรวจผล"
if kubectl -n "$ENV_NAME" logs deployment/backend --tail=500 | grep -q "โปรไฟล์ local"; then
  rollback "backend ใช้โปรไฟล์ local (key ทดสอบ)"
fi
code() { curl -s -o /dev/null -w '%{http_code}' "$@" || true; }
[ "$(code "$PUBLIC_URL/$ENV_NAME/")" = 200 ]                || rollback "หน้าเว็บ /$ENV_NAME/ ไม่ได้ 200"
[ "$(code "$PUBLIC_URL/$ENV_NAME/api/me")" = 401 ]          || rollback "/$ENV_NAME/api/me ไม่ได้ 401 (backend ไม่ตอบ)"
[ "$(code -X POST "$PUBLIC_URL/$ENV_NAME/api/internal/x")" = 404 ] || rollback "/$ENV_NAME/api/internal ไม่ได้ 404"

echo "$TARGET" > "$STATE/$ENV_NAME.deployed"
finish ok "$PUBLIC_URL/$ENV_NAME/"
log "✓ deploy $ENV_NAME @ $SHA สำเร็จ → $PUBLIC_URL/$ENV_NAME/"

# CD อัปเดตตัวเองเป็นเวอร์ชันของ commit ที่ผ่าน uat แล้ว (mv = ไฟล์ใหม่ ไม่กระทบ process ที่รันอยู่)
if [ "$ENV_NAME" = uat ] && [ -d "$CD_HOME/bin" ]; then
  cp "$WORK/devops/cd/deploy.sh" "$CD_HOME/bin/deploy.sh.new" && chmod +x "$CD_HOME/bin/deploy.sh.new" \
    && mv "$CD_HOME/bin/deploy.sh.new" "$CD_HOME/bin/deploy.sh"
fi

# image ในเครื่อง (docker) ไม่ต้องเก็บ — อยู่ใน registry แล้ว · เก็บแค่ของ commit นี้ไว้เป็น cache
docker image ls --format '{{.Repository}}:{{.Tag}}' \
  | grep "^$REGISTRY/bangmod-" | grep -v -e ":$SHA$" -e ":$ENV_NAME-$SHA$" \
  | xargs -r docker rmi >/dev/null 2>&1 || true
