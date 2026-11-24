# AI Service (FastAPI) · โมเดลไม่อยู่ใน image — mount เข้า /models ตอนรัน (ดู README.md)
#   docker build -t bangmod-ai-service:latest ai-service/
FROM python:3.12-slim
WORKDIR /app
# OpenCV / ultralytics ต้องใช้ lib เหล่านี้
RUN apt-get update \
 && apt-get install -y --no-install-recommends libgl1 libglib2.0-0 \
 && rm -rf /var/lib/apt/lists/*
COPY requirements.txt ./
RUN pip install --no-cache-dir -r requirements.txt
COPY *.py ./
RUN useradd --system --uid 10001 app
USER 10001
EXPOSE 8080
ENV YOLO_CONFIG_DIR=/tmp/ultralytics
CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8080"]
