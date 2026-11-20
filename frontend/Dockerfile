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
# nginx image แทนค่า ${APP_BASE} ใน template ตอน start
COPY nginx.conf /etc/nginx/templates/default.conf.template
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
