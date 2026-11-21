package com.faculty.attendance.attendance;

import static com.faculty.attendance.common.Times.NOW_TH;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * attendance_records + class_sessions (กติกา: docs/database/database-design.md "จัดสถานะตอนกล้องเห็นครั้งแรก")
 * CHECK ใน DB ที่ต้องเคารพ: manual ต้องมี recorded_by + reason · absent ⇔ check_in_time IS NULL ·
 * camera ต้องมี match_score · system ต้องเป็น absent · last_seen_at >= check_in_time
 */
@Repository
public class AttendanceRepository {

    private final JdbcTemplate jdbc;

    public AttendanceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** started = เวลาไทยตอนนี้ >= session_date + start_time */
    public record SessionCtx(UUID sessionId, UUID sectionId, UUID roomId, LocalDate sessionDate,
                             LocalTime startTime, LocalTime endTime, LocalTime checkInOpens,
                             LocalTime checkInCloses, LocalTime onTimeUntil, LocalTime lateUntil,
                             String sessionStatus, boolean cameraEnabled, boolean started) {
        public LocalDateTime at(LocalTime t) { return sessionDate.atTime(t); }
    }

    private static final String SESSION_COLUMNS = """
            cs.session_id, cs.section_id, cs.room_id, cs.session_date, cs.start_time, cs.end_time,
            cs.check_in_opens, cs.check_in_closes, cs.on_time_until, cs.late_until, cs.session_status,
            s.camera_attendance_enabled, (cs.session_date + cs.start_time) <= %s AS started
            """.formatted(NOW_TH);

    public Optional<SessionCtx> findSession(UUID sessionId) {
        return jdbc.query("SELECT " + SESSION_COLUMNS + """
                FROM class_sessions cs
                JOIN sections s ON s.section_id = cs.section_id AND s.deleted_at IS NULL
                JOIN courses c ON c.course_id = s.course_id AND c.deleted_at IS NULL
                WHERE cs.session_id = ?
                """, (rs, i) -> session(rs), sessionId).stream().findFirst();
    }

    /** คาบในห้องนี้ที่ช่วงเช็คชื่อเปิดแล้วและยังไม่เลิกเรียน ณ เวลา seenAt (เวลาไทย) */
    public Optional<SessionCtx> sessionInRoomAt(UUID roomId, LocalDateTime seenAt) {
        return jdbc.query("SELECT " + SESSION_COLUMNS + """
                FROM class_sessions cs
                JOIN sections s ON s.section_id = cs.section_id AND s.deleted_at IS NULL
                JOIN courses c ON c.course_id = s.course_id AND c.deleted_at IS NULL
                WHERE cs.room_id = ? AND cs.session_status <> 'cancelled'
                  AND cs.session_date = ?::date
                  AND ?::time BETWEEN cs.check_in_opens AND cs.end_time
                ORDER BY cs.check_in_opens
                LIMIT 1
                """, (rs, i) -> session(rs), roomId, seenAt.toLocalDate(), seenAt.toLocalTime())
                .stream().findFirst();
    }

    public record StudentRow(UUID studentId, String studentCode, String studentName) {}

    /** นักศึกษาที่ลงทะเบียน (active) ใน section ของคาบนี้ */
    public Optional<StudentRow> enrolledStudent(UUID sectionId, UUID studentId) {
        return jdbc.query("""
                SELECT sp.student_id, sp.student_code, u.first_name || ' ' || u.last_name AS name
                FROM enrollments e
                JOIN student_profiles sp ON sp.student_id = e.student_id
                JOIN users u ON u.user_id = sp.student_id
                WHERE e.section_id = ? AND e.student_id = ? AND e.status = 'active'
                """, (rs, i) -> new StudentRow(rs.getObject("student_id", UUID.class),
                        rs.getString("student_code"), rs.getString("name")), sectionId, studentId)
                .stream().findFirst();
    }

    /** consent ล่าสุดเป็น granted (กล้องเขียนได้เฉพาะคนที่ยินยอมอยู่) */
    public boolean hasGrantedConsent(UUID studentId) {
        return !jdbc.queryForList(
                "SELECT 1 FROM v_current_consent WHERE student_id = ? AND action = 'granted'", studentId).isEmpty();
    }

    public record RecordRow(UUID attendanceId, String status, OffsetDateTime checkInTime, OffsetDateTime lastSeenAt, String source,
                            BigDecimal matchScore, UUID recordedBy, String reason) {}

    /** ล็อกแถว (FOR UPDATE) กันเขียนซ้อนจากกล้องกับอาจารย์ */
    public Optional<RecordRow> findRecordForUpdate(UUID sessionId, UUID studentId) {
        return jdbc.query("""
                SELECT attendance_id, status, check_in_time, last_seen_at, source, match_score, recorded_by, reason
                FROM attendance_records WHERE session_id = ? AND student_id = ?
                FOR UPDATE
                """, (rs, i) -> new RecordRow(rs.getObject("attendance_id", UUID.class), rs.getString("status"),
                        rs.getObject("check_in_time", OffsetDateTime.class),
                        rs.getObject("last_seen_at", OffsetDateTime.class), rs.getString("source"),
                        rs.getBigDecimal("match_score"), rs.getObject("recorded_by", UUID.class),
                        rs.getString("reason")), sessionId, studentId).stream().findFirst();
    }

    /** B8 · upsert แถว manual (อาจารย์แก้ได้ทุกแถว รวมแถว camera / system) */
    public void upsertManual(UUID sessionId, UUID studentId, String status, OffsetDateTime checkInTime,
                             UUID recordedBy, String reason) {
        jdbc.update("""
                INSERT INTO attendance_records (session_id, student_id, status, check_in_time, last_seen_at,
                                                source, recorded_by, reason)
                VALUES (?, ?, ?, ?, NULL, 'manual', ?, ?)
                ON CONFLICT (session_id, student_id) DO UPDATE SET
                    status        = EXCLUDED.status,
                    check_in_time = EXCLUDED.check_in_time,
                    last_seen_at  = CASE WHEN EXCLUDED.status = 'absent' OR attendance_records.last_seen_at IS NULL
                                         THEN NULL
                                         ELSE greatest(attendance_records.last_seen_at, EXCLUDED.check_in_time) END,
                    source        = 'manual',
                    recorded_by   = EXCLUDED.recorded_by,
                    reason        = EXCLUDED.reason
                """, sessionId, studentId, status, checkInTime, recordedBy, reason);
    }

    /** กล้องเห็นครั้งแรก · คืน false ถ้ามีแถวอยู่แล้ว (ไม่เขียนทับ) */
    public boolean insertCamera(UUID sessionId, UUID studentId, String status, OffsetDateTime seenAt,
                                BigDecimal matchScore) {
        return jdbc.update("""
                INSERT INTO attendance_records (session_id, student_id, status, check_in_time, last_seen_at,
                                                source, match_score)
                VALUES (?, ?, ?, ?, ?, 'camera', ?)
                ON CONFLICT (session_id, student_id) DO NOTHING
                """, sessionId, studentId, status, seenAt, seenAt, matchScore) == 1;
    }

    /** กล้องเห็นซ้ำ: อัปเดตเฉพาะ last_seen_at ของแถว camera (ไม่แตะแถว manual / system) */
    public void touchCameraLastSeen(UUID sessionId, UUID studentId, OffsetDateTime seenAt) {
        jdbc.update("""
                UPDATE attendance_records SET last_seen_at = greatest(last_seen_at, ?)
                WHERE session_id = ? AND student_id = ? AND source = 'camera'
                """, seenAt, sessionId, studentId);
    }

    // ---------- jobs ----------

    public record StatusChange(UUID sessionId, UUID roomId, String sessionStatus) {}

    /** scheduled -> in_progress -> completed ตามเวลาไทย (ไม่แตะคาบที่ยกเลิก) */
    public List<StatusChange> advanceSessionStatuses() {
        return jdbc.query("""
                UPDATE class_sessions cs SET session_status = CASE
                         WHEN (cs.session_date + cs.end_time) <= %1$s THEN 'completed'
                         ELSE 'in_progress' END
                WHERE cs.session_status IN ('scheduled', 'in_progress')
                  AND (cs.session_date + cs.start_time) <= %1$s
                  AND cs.session_status IS DISTINCT FROM CASE
                         WHEN (cs.session_date + cs.end_time) <= %1$s THEN 'completed'
                         ELSE 'in_progress' END
                RETURNING cs.session_id, cs.room_id, cs.session_status
                """.formatted(NOW_TH), (rs, i) -> new StatusChange(rs.getObject("session_id", UUID.class),
                rs.getObject("room_id", UUID.class), rs.getString("session_status")));
    }

    /**
     * ปิดช่วงเช็คชื่อแล้ว: INSERT absent (source = system) ให้คนที่ยังไม่มีแถว
     * ดูย้อนหลัง lookbackDays วัน · นับเฉพาะคนที่ลงทะเบียนก่อนคาบเริ่ม · รันซ้ำได้ (ON CONFLICT)
     */
    public int insertSystemAbsents(int lookbackDays) {
        return jdbc.update("""
                INSERT INTO attendance_records (session_id, student_id, status, source)
                SELECT cs.session_id, e.student_id, 'absent', 'system'
                FROM class_sessions cs
                JOIN sections s ON s.section_id = cs.section_id AND s.deleted_at IS NULL
                JOIN enrollments e ON e.section_id = cs.section_id AND e.status = 'active'
                WHERE cs.session_status <> 'cancelled'
                  AND (cs.session_date + cs.check_in_closes) <= %1$s
                  AND cs.session_date >= (%1$s)::date - ?
                  AND e.enrolled_at <= ((cs.session_date + cs.start_time) AT TIME ZONE 'Asia/Bangkok')
                ON CONFLICT (session_id, student_id) DO NOTHING
                """.formatted(NOW_TH), lookbackDays);
    }

    private static SessionCtx session(ResultSet rs) throws SQLException {
        return new SessionCtx(rs.getObject("session_id", UUID.class), rs.getObject("section_id", UUID.class),
                rs.getObject("room_id", UUID.class), rs.getDate("session_date").toLocalDate(),
                rs.getTime("start_time").toLocalTime(), rs.getTime("end_time").toLocalTime(),
                rs.getTime("check_in_opens").toLocalTime(), rs.getTime("check_in_closes").toLocalTime(),
                rs.getTime("on_time_until").toLocalTime(), rs.getTime("late_until").toLocalTime(),
                rs.getString("session_status"), rs.getBoolean("camera_attendance_enabled"),
                rs.getBoolean("started"));
    }
}
