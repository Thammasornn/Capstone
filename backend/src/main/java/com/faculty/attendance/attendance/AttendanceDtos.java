package com.faculty.attendance.attendance;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public final class AttendanceDtos {
    private AttendanceDtos() {}

    /**
     * B8 PUT /class-sessions/{sessionId}/attendance/{studentId}
     * status = on_time | late | absent · reason บังคับ (CHECK ใน DB: manual ต้องมีเหตุผล)
     * checkInTime = เวลาไทย ไม่ใส่ = ใช้ของเดิม หรือเวลาเริ่มคาบ (absent ไม่ใช้)
     */
    public record ManualAttendanceRequest(String status, LocalDateTime checkInTime, String reason) {}

    public record ManualAttendanceResponse(UUID sessionId, UUID studentId, String studentCode, String studentName,
                                           String status, LocalDateTime checkInTime, LocalDateTime checkOutTime,
                                           String source, String reason, UUID recordedBy) {}

    // ---- AI Service -> backend (/api/internal/**) ----

    /** heartbeat ของกล้อง · status = live | no_signal */
    public record CameraHeartbeat(String status) {}

    /** จำนวนใบหน้าในเฟรม (R1 ส่งต่อทาง SSE อย่างเดียว ไม่เก็บ) */
    public record FacesReport(Integer detected, LocalDateTime at) {}

    /** กล้องจับคู่ใบหน้าได้ · seenAt = เวลาไทย (ไม่ใส่ = ตอนนี้) · matchScore = SFace cosine similarity */
    public record Sighting(UUID studentId, BigDecimal matchScore, LocalDateTime seenAt) {}

    /**
     * result: checked_in (บันทึกครั้งแรก) | seen (อัปเดต last_seen_at) | ignored
     * reason (เมื่อ ignored): no_session | camera_disabled | not_enrolled | no_consent | check_in_closed | manual_record
     */
    public record SightingResult(String result, String reason, UUID sessionId, String status) {}
}
