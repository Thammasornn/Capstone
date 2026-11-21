#!/usr/bin/env bash
# =====================================================================
# Bangmod Smart Attendance — ทดสอบบนเครื่องนักพัฒนา
#
#   ./dev.sh db          สร้างฐานข้อมูล + ใส่ข้อมูล mock (รันซ้ำ = ล้างข้อมูลกลับเป็นชุดเดิม)
#   ./dev.sh db --reset  ลบฐานข้อมูลทิ้งแล้วสร้างใหม่ทั้งหมด (ใช้เมื่อ schema เปลี่ยน)
#   ./dev.sh             รัน backend + frontend พร้อมกัน (Ctrl+C หยุดทั้งคู่)
#   ./dev.sh backend     รันเฉพาะ backend   ·   ./dev.sh frontend   รันเฉพาะ frontend
#   ./dev.sh test        ตรวจอัตโนมัติ: backend compile + frontend unit test / lint / format / build
#   ./dev.sh smoke       ยิง API จริงกับ backend ที่รันอยู่ (อ่านอย่างเดียว ไม่แก้ข้อมูล)
#
# ต้องมี: PostgreSQL ที่เปิดอยู่ (เช่น Postgres.app), Java 25 + Maven, Node.js 22.18+ / 24.12+
# ต่อ PostgreSQL อื่น: PGHOST / PGPORT / PGUSER / PGPASSWORD (ค่าเริ่มต้น localhost / 5432 / postgres)
#   ถ้าไม่ใช่ค่าเริ่มต้น ให้ใส่ DB_URL / DB_USER / DB_PASSWORD ใน backend/.env.local ด้วย
# port ชนกับโปรแกรมอื่น: BACKEND_PORT=8082 FRONTEND_PORT=5174 ./dev.sh
# =====================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
DB_DIR="$ROOT/backend/db"
DEV_DIR="$ROOT/.dev"
DB_NAME=bangmod_attendance
BACKEND_PORT="${BACKEND_PORT:-8081}"
FRONTEND_PORT="${FRONTEND_PORT:-5173}"

export PGHOST="${PGHOST:-localhost}" PGPORT="${PGPORT:-5432}" PGUSER="${PGUSER:-postgres}"

bold() { printf '\033[1m%s\033[0m\n' "$*"; }
info() { printf '\033[36m▸ %s\033[0m\n' "$*"; }
ok()   { printf '\033[32m✓ %s\033[0m\n' "$*"; }
die()  { printf '\033[31m✗ %s\033[0m\n' "$*" >&2; exit 1; }

# ---------- tools ----------

find_psql() {
  if command -v psql >/dev/null 2>&1; then return; fi
  local app="/Applications/Postgres.app/Contents/Versions/latest/bin"
  if [ -x "$app/psql" ]; then export PATH="$app:$PATH"; return; fi
  die "ไม่พบ psql — ติดตั้ง Postgres.app (https://postgresapp.com) หรือ PostgreSQL ก่อน"
}

require_db_server() {
  find_psql
  pg_isready -q -h "$PGHOST" -p "$PGPORT" \
    || die "ต่อ PostgreSQL ที่ $PGHOST:$PGPORT ไม่ได้ — เปิด Postgres.app แล้วกด Start ก่อน"
}

db_exists() {
  [ "$(psql -d postgres -tAc "SELECT 1 FROM pg_database WHERE datname = '$DB_NAME'")" = "1" ]
}

port_in_use() { lsof -nP -iTCP:"$1" -sTCP:LISTEN >/dev/null 2>&1; }

run_sql() { psql -d "$DB_NAME" -v ON_ERROR_STOP=1 -q -f "$1" >/dev/null; }

# ---------- db ----------

cmd_db() {
  require_db_server
  if [ "${1:-}" = "--reset" ] && db_exists; then
    printf 'จะลบฐานข้อมูล %s ในเครื่องนี้ทิ้งทั้งหมด ยืนยัน? [y/N] ' "$DB_NAME"
    read -r answer
    [ "$answer" = "y" ] || [ "$answer" = "Y" ] || die "ยกเลิก"
    psql -d postgres -q -c "DROP DATABASE $DB_NAME WITH (FORCE)"
    ok "ลบ $DB_NAME แล้ว"
  fi

  if ! db_exists; then
    info "สร้างฐานข้อมูล $DB_NAME"
    psql -d postgres -v ON_ERROR_STOP=1 -q -f "$DB_DIR/00_create_database.sql" >/dev/null
  fi

  if [ "$(psql -d "$DB_NAME" -tAc "SELECT to_regclass('public.users') IS NOT NULL")" != "t" ]; then
    info "สร้างตาราง (01_schema.sql)"
    run_sql "$DB_DIR/01_schema.sql"
  fi

  info "ใส่ข้อมูล mock (02_seed_mock.sql) — ล้างข้อมูลเดิมทุกตาราง"
  run_sql "$DB_DIR/02_seed_mock.sql"
  info "สร้างคาบที่กำลังเรียนอยู่ตอนนี้ (03_demo_live_session.sql)"
  run_sql "$DB_DIR/03_demo_live_session.sql"
  local check
  check="$(psql -d "$DB_NAME" -v ON_ERROR_STOP=1 -q -f "$DB_DIR/test/assertions.sql" 2>&1 || true)"
  case "$check" in
    *"All database checks passed"*) ;;
    *) echo "$check" >&2; die "ตรวจข้อมูลไม่ผ่าน (test/assertions.sql) — ลอง ./dev.sh db --reset" ;;
  esac
  ok "ฐานข้อมูลพร้อม ($PGHOST:$PGPORT/$DB_NAME)"
}

# ---------- run ----------

check_db_ready() {
  require_db_server
  db_exists || die "ยังไม่มีฐานข้อมูล $DB_NAME — รัน ./dev.sh db ก่อน"
}

start_backend_bg() {
  command -v mvn >/dev/null 2>&1 || die "ไม่พบ mvn (Maven) — ติดตั้ง Java 25 + Maven ก่อน"
  if port_in_use "$BACKEND_PORT"; then
    info "port $BACKEND_PORT มีโปรแกรมใช้อยู่แล้ว — ใช้ backend ตัวที่รันอยู่"
    return
  fi
  mkdir -p "$DEV_DIR"
  info "เปิด backend (log: .dev/backend.log) — ครั้งแรกอาจนานเพราะ Maven ต้องโหลด dependency"
  (cd "$ROOT/backend" && SERVER_PORT="$BACKEND_PORT" exec mvn -q spring-boot:run) > "$DEV_DIR/backend.log" 2>&1 &
  BACKEND_PID=$!
  local waited=0
  until port_in_use "$BACKEND_PORT"; do
    if ! kill -0 "$BACKEND_PID" 2>/dev/null; then
      tail -30 "$DEV_DIR/backend.log" >&2
      die "backend หยุดทำงาน — ดู log ด้านบน หรือ .dev/backend.log"
    fi
    [ "$waited" -ge 300 ] && die "backend ไม่ขึ้นภายใน 5 นาที — ดู .dev/backend.log"
    sleep 1; waited=$((waited + 1))
  done
  ok "backend พร้อม: http://localhost:$BACKEND_PORT"
}

stop_backend() {
  if [ -n "${BACKEND_PID:-}" ] && kill -0 "$BACKEND_PID" 2>/dev/null; then
    info "หยุด backend"
    pkill -P "$BACKEND_PID" 2>/dev/null || true
    kill "$BACKEND_PID" 2>/dev/null || true
  fi
  BACKEND_PID=""
}

run_frontend() {
  command -v npm >/dev/null 2>&1 || die "ไม่พบ npm — ติดตั้ง Node.js ก่อน"
  port_in_use "$FRONTEND_PORT" && die "port $FRONTEND_PORT มีโปรแกรมใช้อยู่ — ปิด npm run dev ตัวเก่าก่อน"
  cd "$ROOT/frontend"
  if [ ! -d node_modules ] || [ package-lock.json -nt node_modules ]; then
    info "ติดตั้ง package ของ frontend (npm ci)"
    npm ci --no-audit --no-fund
  fi
  print_accounts
  DEV_BACKEND_URL="${DEV_BACKEND_URL:-http://localhost:$BACKEND_PORT}" \
    npm run dev -- --port "$FRONTEND_PORT" --strictPort
}

print_accounts() {
  echo
  bold "เปิด http://localhost:$FRONTEND_PORT · รหัสผ่านทุกบัญชี Password@123"
  echo "  admin     admin@sit.kmutt.ac.th"
  echo "  lecturer  supawadee.c@sit.kmutt.ac.th   (INT201 มีคาบกำลังเรียนในห้อง 4302)"
  echo "  lecturer  thanakorn.w@sit.kmutt.ac.th"
  echo "  student   69130500001@st.sit.kmutt.ac.th"
  echo "  /api/internal/** ทดสอบด้วย header  X-Internal-Key: local-test-key"
  echo
}

cmd_all() {
  check_db_ready
  trap stop_backend EXIT
  trap 'exit 130' INT TERM
  start_backend_bg
  run_frontend
}

# ---------- test ----------

STEP_FAIL=0
step() {   # step "ชื่อ" คำสั่ง...
  local name="$1"; shift
  printf '\033[36m▸ %s\033[0m ... ' "$name"
  local out
  if out="$("$@" 2>&1)"; then
    printf '\033[32mผ่าน\033[0m\n'
  else
    printf '\033[31mไม่ผ่าน\033[0m\n'; echo "$out" | tail -25 >&2; STEP_FAIL=$((STEP_FAIL + 1))
  fi
}

cmd_test() {
  command -v mvn >/dev/null 2>&1 || die "ไม่พบ mvn (Maven)"
  command -v npm >/dev/null 2>&1 || die "ไม่พบ npm"
  bold "ตรวจอัตโนมัติ (ไม่ต้องเปิด backend / ฐานข้อมูล)"
  step "dev.sh syntax"              bash -n "$0"
  step "backend: mvn compile"       bash -c "cd '$ROOT/backend' && mvn -q -B compile"
  if [ ! -d "$ROOT/frontend/node_modules" ] || [ "$ROOT/frontend/package-lock.json" -nt "$ROOT/frontend/node_modules" ]; then
    step "frontend: npm ci"         bash -c "cd '$ROOT/frontend' && npm ci --no-audit --no-fund"
  fi
  step "frontend: unit test (Vitest)" bash -c "cd '$ROOT/frontend' && npm test"
  step "frontend: lint"             bash -c "cd '$ROOT/frontend' && npm run lint"
  step "frontend: format"           bash -c "cd '$ROOT/frontend' && npm run format:check"
  step "frontend: build (prod)"     bash -c "cd '$ROOT/frontend' && npm run build"
  step "frontend: build (uat)"      bash -c "cd '$ROOT/frontend' && npm run build:uat"
  echo
  [ "$STEP_FAIL" -eq 0 ] || die "ไม่ผ่าน $STEP_FAIL ข้อ (ดูรายละเอียดด้านบน)"
  ok "ผ่านทั้งหมด · ตรวจฐานข้อมูล: ./dev.sh db · ตรวจ API จริง: ./dev.sh แล้ว ./dev.sh smoke"
}

# ---------- smoke ----------

SMOKE_PASS=0 SMOKE_FAIL=0
expect() {   # expect "ชื่อ" "<ค่าที่ได้>" "<ค่าที่ต้องการ>"
  if [ "$2" = "$3" ]; then
    printf '  \033[32m✓\033[0m %s\n' "$1"; SMOKE_PASS=$((SMOKE_PASS + 1))
  else
    printf '  \033[31m✗\033[0m %s (ได้ "%s" ต้องการ "%s")\n' "$1" "$2" "$3"; SMOKE_FAIL=$((SMOKE_FAIL + 1))
  fi
}

cmd_smoke() {
  command -v jq >/dev/null 2>&1 || die "ต้องมี jq (brew install jq)"
  local api="http://localhost:$BACKEND_PORT/api" key="${INTERNAL_API_KEY:-local-test-key}"
  port_in_use "$BACKEND_PORT" || die "backend ไม่ได้รันที่ port $BACKEND_PORT — เปิดด้วย ./dev.sh หรือ ./dev.sh backend ก่อน"
  bold "Smoke test: $api (อ่านอย่างเดียว · ต้องใช้ข้อมูลจาก ./dev.sh db)"

  login() {
    curl -s "$api/auth/login" -H 'Content-Type: application/json' \
      -d "{\"email\":\"$1\",\"password\":\"Password@123\"}" | jq -r '.accessToken // empty'
  }
  code() { curl -s -o /dev/null -w '%{http_code}' "$@"; }

  local admin lect stu
  admin="$(login admin@sit.kmutt.ac.th)"; lect="$(login supawadee.c@sit.kmutt.ac.th)"
  stu="$(login 69130500001@st.sit.kmutt.ac.th)"
  echo "Auth (5.1–5.2)"
  expect "login admin / lecturer / student" "$([ -n "$admin" ] && [ -n "$lect" ] && [ -n "$stu" ] && echo ok)" "ok"
  [ -n "$admin" ] || die "login ไม่ได้ — รัน ./dev.sh db เพื่อใส่ข้อมูล mock ก่อน"
  expect "รหัสผิด → 401 + ข้อความ" \
    "$(curl -s "$api/auth/login" -H 'Content-Type: application/json' -d '{"email":"admin@sit.kmutt.ac.th","password":"x"}' | jq -r '"\(.status) \(.message != null)"')" "401 true"
  expect "GET /me (student)" "$(curl -s -H "Authorization: Bearer $stu" "$api/me" | jq -r .role)" "student"
  expect "ไม่มี token → 401" "$(code "$api/me")" "401"

  echo "Dashboard (5.3–5.8)"
  local course
  course="$(curl -s -H "Authorization: Bearer $lect" "$api/courses" | jq -r '.[0].courseId // empty')"
  expect "GET /courses (lecturer) มีรายวิชา" "$([ -n "$course" ] && echo ok)" "ok"
  expect "GET /courses มีตารางเรียน (B5)" \
    "$(curl -s -H "Authorization: Bearer $lect" "$api/courses" | jq -r '.[0].sections[0].classDays | length > 0')" "true"
  expect "สรุปรายวิชา (5.4)" "$(code -H "Authorization: Bearer $lect" "$api/courses/$course/attendance/summary")" "200"
  expect "student ดูสรุปรายวิชา → 403" "$(code -H "Authorization: Bearer $stu" "$api/courses/$course/attendance/summary")" "403"

  echo "Room Monitor (B1–B4)"
  local room
  room="$(curl -s -H "Authorization: Bearer $lect" "$api/rooms" | jq -r '.[0].roomId // empty')"
  expect "GET /rooms (lecturer)" "$([ -n "$room" ] && echo ok)" "ok"
  expect "student ดูห้อง → 403" "$(code -H "Authorization: Bearer $stu" "$api/rooms")" "403"
  expect "GET /rooms/{id} (B4)" "$(code -H "Authorization: Bearer $lect" "$api/rooms/$room")" "200"
  expect "GET /rooms/{id}/stream (B2)" "$(curl -s -H "Authorization: Bearer $lect" "$api/rooms/$room/stream" | jq -r .protocol)" "whep"
  expect "SSE /rooms/{id}/events (B3) ส่ง event camera" \
    "$(curl -s -N --max-time 3 "$api/rooms/$room/events?access_token=$lect" | grep -c '^event:camera' | tr -d ' ')" "1"

  echo "Internal (AI Service → backend)"
  expect "ไม่มี X-Internal-Key → 401" \
    "$(code -X POST "$api/internal/rooms/$room/faces" -H 'Content-Type: application/json' -d '{"detected":1}')" "401"
  expect "มี key → 200 (ส่ง event faces อย่างเดียว ไม่เขียน DB)" \
    "$(code -X POST "$api/internal/rooms/$room/faces" -H "X-Internal-Key: $key" -H 'Content-Type: application/json' -d '{"detected":1}')" "200"

  echo
  [ "$SMOKE_FAIL" -eq 0 ] || die "ผ่าน $SMOKE_PASS · ไม่ผ่าน $SMOKE_FAIL"
  ok "ผ่านทั้งหมด $SMOKE_PASS ข้อ"
}

cmd_backend() {
  check_db_ready
  command -v mvn >/dev/null 2>&1 || die "ไม่พบ mvn (Maven)"
  cd "$ROOT/backend" && SERVER_PORT="$BACKEND_PORT" exec mvn spring-boot:run
}

case "${1:-}" in
  "")        cmd_all ;;
  db)        shift; cmd_db "$@" ;;
  backend)   cmd_backend ;;
  frontend)  run_frontend ;;
  test)      cmd_test ;;
  smoke)     cmd_smoke ;;
  -h|--help|help) sed -n '3,15p' "$0" | sed 's/^# \{0,1\}//' ;;
  *)         die "ไม่รู้จักคำสั่ง '$1' — ดู ./dev.sh help" ;;
esac
