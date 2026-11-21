# Build → NGINX · ใช้ทั้ง UAT และ Production (K3s แยก namespace)
#   docker build -t bangmod-frontend:prod .
#   docker build --build-arg MODE=uat --build-arg APP_BASE=/uat/ -t bangmod-frontend:uat .
FROM node:22-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
ARG MODE=production
RUN npx vite build --mode ${MODE}

FROM nginx:1.27-alpine
# ต้องตรงกับ VITE_BASE ใน .env.<MODE>
ARG APP_BASE=/prod/
ENV APP_BASE=${APP_BASE}
# Spring Boot ที่ NGINX ส่ง ${APP_BASE}api/* ไปหา · เปลี่ยนได้ตอนรัน (K3s: env ของ Deployment)
#   docker run -e BACKEND_URL=http://10.0.0.5:8081 ...
ENV BACKEND_URL=http://backend:8081
# nginx image แทนค่า ${APP_BASE} / ${BACKEND_URL} ใน template ตอน start (ตัวแปรของ nginx เช่น $host ไม่ถูกแตะ)
COPY nginx.conf /etc/nginx/templates/default.conf.template
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
