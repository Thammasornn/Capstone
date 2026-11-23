# Build → NGINX · ใช้ทั้ง UAT และ Production (K3s แยก namespace)
#   docker build -t bangmod-frontend:prod .
#   docker build --build-arg MODE=uat -t bangmod-frontend:uat .
# path ของเว็บ (เช่น /prod/, /capstone26/uat/) มาจาก VITE_BASE ใน .env.<MODE> ที่เดียว
# ทั้ง Vue (vite build) และ NGINX (location) ใช้ค่านี้ — ไม่ต้องส่ง APP_BASE แยก
FROM node:22-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
ARG MODE=production
RUN npx vite build --mode ${MODE}
# ใส่ VITE_BASE ลงใน nginx.conf ตอน build (${BACKEND_URL} เหลือไว้ให้ nginx แทนค่าตอน start)
RUN APP_BASE="$(sed -n 's/^VITE_BASE=//p' .env.${MODE})" \
  && case "$APP_BASE" in /*/) ;; *) echo "VITE_BASE ใน .env.${MODE} ต้องขึ้นต้นและลงท้ายด้วย / (ได้ '$APP_BASE')"; exit 1 ;; esac \
  && sed "s#\${APP_BASE}#${APP_BASE}#g" nginx.conf > /app/nginx.conf.template \
  && echo "APP_BASE=${APP_BASE}"

FROM nginx:1.27-alpine
# Spring Boot ที่ NGINX ส่ง <base>api/* ไปหา · เปลี่ยนได้ตอนรัน (K3s: env ของ Deployment)
#   docker run -e BACKEND_URL=http://10.0.0.5:8081 ...
ENV BACKEND_URL=http://backend:8081
# nginx image แทนค่า ${BACKEND_URL} ใน template ตอน start (ตัวแปรของ nginx เช่น $host ไม่ถูกแตะ)
COPY --from=build /app/nginx.conf.template /etc/nginx/templates/default.conf.template
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
