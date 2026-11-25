package com.faculty.attendance.attendance;

import com.faculty.attendance.attendance.SessionControlRepository.Times;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** ปรับเวลาคาบที่เริ่มแล้ว (SessionAdjustService) · ทุกเมธอดเรียกภายใน transaction เดียว */
@Repository
public class SessionAdjustRepository {

    public record LockedSession(UUID sessionId, UUID sectionId, UUID roomId, LocalDate sessionDate, Times times,
                                String sessionStatus, String label) {}

    public record AttendanceRow(UUID attendanceId, UUID studentId, String studentCode, String studentName,
                                String status, OffsetDateTime checkInTime, String source) {}

    private final JdbcTemplate jdbc;

    public SessionAdjustRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** ล็อกแถว class_sessions (กันชนกับ AttendanceJobs / กดซ้อน) · ไม่นับวิชา / กลุ่มเรียนที่ถูกลบ */
    public Optional<LockedSession> lockSession(UUID sessionId) {
        return jdbc.query("""
                SELECT cs.session_id, cs.section_id, cs.room_id, cs.session_date, cs.start_time, cs.end_time,
                       cs.check_in_opens, cs.on_time_until, cs.late_until, cs.check_in_closes, cs.session_status,
                       c.course_code || '-' || s.section_code AS label
                FROM class_sessions cs
                JOIN sections s ON s.section_id = cs.section_id AND s.deleted_at IS NULL
                JOIN courses c ON c.course_id = s.course_id AND c.deleted_at IS NULL
                WHERE cs.session_id = ?
                FOR UPDATE OF cs
                """, (rs, i) -> new LockedSession(rs.getObject("session_id", UUID.class),
                        rs.getObject("section_id", UUID.class), rs.getObject("room_id", UUID.class),
                        rs.getDate("session_date").toLocalDate(),
                        new Times(time(rs, "start_time"), time(rs, "end_time"), time(rs, "check_in_opens"),
                                time(rs, "on_time_until"), time(rs, "late_until"), time(rs, "check_in_closes")),
                        rs.getString("session_status"), rs.getString("label")), sessionId)
                .stream().findFirst();
    }

    /** ผลเช็คชื่อทุกแถวของคาบ (ล็อกแถวกันกล้อง / อาจารย์เขียนซ้อน) */
    public List<AttendanceRow> lockRecords(UUID sessionId) {
        return jdbc.query("""
                SELECT a.attendance_id, a.student_id, sp.student_code, u.first_name || ' ' || u.last_name AS name,
                       a.status, a.check_in_time, a.source
                FROM attendance_records a
                JOIN student_profiles sp ON sp.student_id = a.student_id
                JOIN users u ON u.user_id = a.student_id
                WHERE a.session_id = ?
                ORDER BY sp.student_code
                FOR UPDATE OF a
                """, (rs, i) -> new AttendanceRow(rs.getObject("attendance_id", UUID.class),
                        rs.getObject("student_id", UUID.class), rs.getString("student_code"), rs.getString("name"),
                        rs.getString("status"), rs.getObject("check_in_time", OffsetDateTime.class),
                        rs.getString("source")), sessionId);
    }

    /** คาบอื่นในห้องเดียวกันวันเดียวกันที่เวลาทับกัน (ไม่นับ cancelled) → ชื่อวิชา-กลุ่ม */
    public Optional<String> roomConflict(UUID roomId, LocalDate date, LocalTime from, LocalTime to, UUID exceptSession) {
        return jdbc.queryForList("""
                SELECT c.course_code || '-' || s.section_code
                FROM class_sessions cs
                JOIN sections s ON s.section_id = cs.section_id
                JOIN courses c ON c.course_id = s.course_id
                WHERE cs.room_id = ? AND cs.session_date = ? AND cs.session_id <> ?
                  AND cs.session_status <> 'cancelled'
                  AND cs.start_time < ? AND cs.end_time > ?
                ORDER BY cs.start_time LIMIT 1
                """, String.class, roomId, date, exceptSession, to, from).stream().findFirst();
    }

    public void updateTimes(UUID sessionId, Times t) {
        jdbc.update("""
                UPDATE class_sessions SET start_time = ?, end_time = ?, check_in_opens = ?, on_time_until = ?,
                       late_until = ?, check_in_closes = ?
                WHERE session_id = ?
                """, t.start(), t.end(), t.opens(), t.onTimeUntil(), t.lateUntil(), t.closes(), sessionId);
    }

    /** คิดสถานะใหม่ของแถวกล้อง (คง source / match_score / check_in_time เดิม) */
    public void updateCameraStatus(UUID attendanceId, String status) {
        jdbc.update("UPDATE attendance_records SET status = ? WHERE attendance_id = ? AND source = 'camera'",
                status, attendanceId);
    }

    /** เปิดเช็คชื่อใหม่: ลบแถว absent ที่ระบบใส่ไว้ ให้กล้องบันทึกได้อีก */
    public void deleteSystemAbsent(UUID attendanceId) {
        jdbc.update("DELETE FROM attendance_records WHERE attendance_id = ? AND source = 'system'", attendanceId);
    }

    private static LocalTime time(java.sql.ResultSet rs, String col) throws java.sql.SQLException {
        return rs.getTime(col).toLocalTime();
    }
}
