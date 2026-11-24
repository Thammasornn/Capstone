# Build → NGINX · ใช้ทั้ง UAT และ Production (K3s แยก namespace)
#   docker build -t bangmod-frontend:prod .
#   docker build --build-arg MODE=uat -t bangmod-frontend:uat .
# path มาจาก .env.<MODE> ที่เดียว:
#   VITE_BASE = path ที่เบราว์เซอร์เห็น (Vue ใช้อ้าง assets / router)
#   APP_PATH  = path ที่ request มาถึง VM จริง (NGINX location, ต้องตรงกับ Ingress) — ไม่ใส่ = เท่ากับ VITE_BASE
#   ต่างกันเมื่อมี proxy ข้างหน้าตัด prefix ออก (เช่น bscit ตัด /capstone26/cp26ms3 ก่อนส่งมา VM)
FROM node:22-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
ARG MODE=production
RUN npx vite build --mode ${MODE}
# ใส่ APP_PATH (หรือ VITE_BASE) ลงใน nginx.conf ตอน build (${BACKEND_URL} เหลือไว้ให้ nginx แทนค่าตอน start)
RUN APP_BASE="$(sed -n 's/^APP_PATH=//p' .env.${MODE})" \
  && APP_BASE="${APP_BASE:-$(sed -n 's/^VITE_BASE=//p' .env.${MODE})}" \
  && case "$APP_BASE" in /*/) ;; *) echo "APP_PATH / VITE_BASE ใน .env.${MODE} ต้องขึ้นต้นและลงท้ายด้วย / (ได้ '$APP_BASE')"; exit 1 ;; esac \
  && sed "s#\${APP_BASE}#${APP_BASE}#g" nginx.conf > /app/nginx.conf.template \
  && echo "APP_BASE=${APP_BASE}"

FROM nginx:1.27-alpine
# Spring Boot ที่ NGINX ส่ง <base>api/* ไปหา · เปลี่ยนได้ตอนรัน (K3s: env ของ Deployment)
#   docker run -e BACKEND_URL=http://10.0.0.5:8081 ...
ENV BACKEND_URL=http://backend:8081
# MediaMTX ที่ NGINX ส่ง <base>media/* ไปหา (ภาพสด WHEP) · ต้องเป็นชื่อเต็ม (resolver ของ nginx ไม่ใช้ search domain)
ENV MEDIAMTX_URL=http://mediamtx.media.svc.cluster.local:8889
# ให้ entrypoint ของ nginx image อ่าน nameserver จาก /etc/resolv.conf เป็น ${NGINX_LOCAL_RESOLVERS}
ENV NGINX_ENTRYPOINT_LOCAL_RESOLVERS=1
# nginx image แทนค่า ${BACKEND_URL} / ${MEDIAMTX_URL} / ${NGINX_LOCAL_RESOLVERS} ใน template ตอน start
# (แทนเฉพาะชื่อที่มี env อยู่จริง — ตัวแปรของ nginx เช่น $host, $mediamtx, $1 ไม่ถูกแตะ)
COPY --from=build /app/nginx.conf.template /etc/nginx/templates/default.conf.template
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
