#!/usr/bin/env bash
# build image ทั้งหมดแล้ว import เข้า K3s (containerd) บน VM โดยไม่ต้องมี registry
#   ./devops/k8s/build-images.sh          # build + import (ต้องมี docker และสิทธิ์ sudo k3s)
#   NO_IMPORT=1 ./devops/k8s/build-images.sh   # build อย่างเดียว (เช่นบนเครื่องอื่น แล้ว docker save ไป import บน VM)
set -euo pipefail
cd "$(dirname "$0")/../.."

docker build -t bangmod-backend:latest backend/
docker build -t bangmod-ai-service:latest ai-service/
docker build -t bangmod-frontend:prod frontend/
docker build --build-arg MODE=uat --build-arg APP_BASE=/uat/ -t bangmod-frontend:uat frontend/

if [ -z "${NO_IMPORT:-}" ]; then
  docker save bangmod-backend:latest bangmod-ai-service:latest bangmod-frontend:prod bangmod-frontend:uat \
    | sudo k3s ctr images import -
fi
