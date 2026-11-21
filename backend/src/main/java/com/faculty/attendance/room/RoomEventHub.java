package com.faculty.attendance.room;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * B3: กระจาย event ของห้องให้ทุก client ที่เปิด GET /rooms/{roomId}/events อยู่ (เก็บในหน่วยความจำ)
 * event: camera {cameraStatus} · faces {detected, at} · check_in {...} · session {sessionId, sessionStatus}
 * ⚠️ รันหลาย replica ต้องเปลี่ยนไปใช้ pub/sub กลาง (เช่น PostgreSQL LISTEN/NOTIFY หรือ Redis)
 */
@Component
public class RoomEventHub {

    private static final long TIMEOUT_MS = 30 * 60 * 1000L;   // FE (EventSource) ต่อใหม่เองเมื่อหมดเวลา

    private final Map<UUID, List<SseEmitter>> rooms = new ConcurrentHashMap<>();

    public SseEmitter subscribe(UUID roomId) {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        List<SseEmitter> list = rooms.computeIfAbsent(roomId, k -> new CopyOnWriteArrayList<>());
        list.add(emitter);
        Runnable remove = () -> list.remove(emitter);
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(e -> remove.run());
        return emitter;
    }

    public void send(SseEmitter emitter, String event, Object data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(data));
        } catch (IOException | IllegalStateException e) {
            emitter.completeWithError(e);
        }
    }

    public void publish(UUID roomId, String event, Object data) {
        List<SseEmitter> list = rooms.get(roomId);
        if (list == null) return;
        for (SseEmitter emitter : list) {
            send(emitter, event, data);
        }
    }

    /** comment ทุก 25 วินาที กัน proxy ตัดการเชื่อมต่อที่เงียบ และเก็บกวาด client ที่หลุดไปแล้ว */
    @Scheduled(fixedRate = 25_000)
    void keepAlive() {
        rooms.forEach((roomId, list) -> {
            for (SseEmitter emitter : list) {
                try {
                    emitter.send(SseEmitter.event().comment("ping"));
                } catch (IOException | IllegalStateException e) {
                    emitter.completeWithError(e);
                }
            }
        });
    }
}
