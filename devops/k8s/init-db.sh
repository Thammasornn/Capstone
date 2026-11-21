#!/usr/bin/env bash
# ใส่ schema (ครั้งแรกเท่านั้น) ลง PostgreSQL ใน namespace ที่ระบุ
#   ./devops/k8s/init-db.sh prod
#   ./devops/k8s/init-db.sh uat --seed     # + ข้อมูล mock (02) และคาบเดโม (03) — ห้ามใช้กับ prod
set -euo pipefail
NS="${1:?ระบุ namespace: prod หรือ uat}"
SEED="${2:-}"
cd "$(dirname "$0")/../../backend/db"

psql_in() {
  kubectl -n "$NS" exec -i statefulset/postgres -- \
    psql -U postgres -d bangmod_attendance -v ON_ERROR_STOP=1 -q "$@"
}

kubectl -n "$NS" rollout status statefulset/postgres --timeout=180s
psql_in -c "ALTER DATABASE bangmod_attendance SET timezone TO 'Asia/Bangkok'"
psql_in < 01_schema.sql
if [ "$SEED" = "--seed" ]; then
  [ "$NS" = "prod" ] && { echo "ห้ามใส่ข้อมูล mock ใน prod" >&2; exit 1; }
  psql_in < 02_seed_mock.sql
  psql_in < 03_demo_live_session.sql
  psql_in < test/assertions.sql      # ตรวจ schema + ข้อมูล mock (ใช้ได้เฉพาะตอนมี seed)
fi
echo "เสร็จ: schema ของ namespace $NS พร้อมใช้"
