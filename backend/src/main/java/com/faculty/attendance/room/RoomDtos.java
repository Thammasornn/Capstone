package com.faculty.attendance.room;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.UUID;

/** shape ตามเอกสารแจ้ง Back-End (B1–B4) และ mock ของ FE (frontend/src/mocks/handlers/pending.js) */
public final class RoomDtos {
    private RoomDtos() {}

    /** คาบที่กำลังเรียนอยู่ในห้องตอนนี้ (null = "Class not started") */
    public record CurrentSession(UUID sessionId, String courseCode, String courseName, String sectionCode,
                                 LocalTime startTime, LocalTime endTime, int checkedIn, int totalStudents) {}

    /** B1 / B4 · cameraStatus = live | no_signal (ไม่มีกล้อง = null) */
    public record Room(UUID roomId, String roomCode, String cameraCode, String cameraStatus,
                       CurrentSession currentSession) {}

    /** B2 · protocol = whep · expiresAt = เวลาไทย */
    public record StreamInfo(String protocol, String url, LocalDateTime expiresAt) {}

    // ---- SSE payload (B3) ----
    public record CameraEvent(String cameraStatus) {}

    public record FacesEvent(int detected, LocalDateTime at) {}

    public record CheckInEvent(UUID sessionId, UUID studentId, String studentCode, LocalDateTime checkInTime,
                               String status, String source) {}

    public record SessionEvent(UUID sessionId, String sessionStatus) {}
}
