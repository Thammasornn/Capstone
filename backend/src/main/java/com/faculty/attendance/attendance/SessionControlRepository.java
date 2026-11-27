package com.faculty.attendance.attendance;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** เริ่ม / จบคาบจากหน้าเว็บ (SessionControlService) */
@Repository
public class SessionControlRepository {

    /** ตั้งค่าเวลาของกลุ่มเรียน (ใช้คำนวณความยาวคาบ / ช่วงเช็คชื่อของคาบที่เริ่มตอนนี้) */
    public record SectionTimes(UUID sectionId, LocalTime startTime, LocalTime endTime, LocalTime checkInOpens,
                               LocalTime checkInCloses, int onTimeMinutes, LocalTime lateUntil) {}

    public record SessionRow(UUID sessionId, UUID sectionId, UUID roomId, LocalDate sessionDate, LocalTime startTime,
                             LocalTime endTime, LocalTime checkInOpens, LocalTime onTimeUntil, LocalTime lateUntil,
                             LocalTime checkInCloses, String sessionStatus) {}

    public record Times(LocalTime start, LocalTime end, LocalTime opens, LocalTime onTimeUntil, LocalTime lateUntil,
                        LocalTime closes) {}

    private static final String SESSION_COLUMNS = """
            session_id, section_id, room_id, session_date, start_time, end_time, check_in_opens,
            on_time_until, late_until, check_in_closes, session_status""";

    private final JdbcTemplate jdbc;

    public SessionControlRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<SectionTimes> section(UUID sectionId) {
        return jdbc.query("""
                SELECT s.section_id, s.start_time, s.end_time, s.check_in_opens, s.check_in_closes,
                       s.on_time_minutes, s.late_until
                FROM sections s JOIN courses c ON c.course_id = s.course_id AND c.deleted_at IS NULL
                WHERE s.section_id = ? AND s.deleted_at IS NULL
                """, (rs, i) -> new SectionTimes(rs.getObject("section_id", UUID.class),
                        rs.getTime("start_time").toLocalTime(), rs.getTime("end_time").toLocalTime(),
                        rs.getTime("check_in_opens").toLocalTime(), rs.getTime("check_in_closes").toLocalTime(),
                        rs.getInt("on_time_minutes"),
                        rs.getTime("late_until") == null ? null : rs.getTime("late_until").toLocalTime()),
                sectionId).stream().findFirst();
    }

    /** คาบของกลุ่มเรียนนี้ในวันที่ระบุ (1 กลุ่มมีได้ 1 คาบต่อวัน) · ล็อกแถวกันกดซ้อน */
    public Optional<SessionRow> sessionOn(UUID sectionId, LocalDate date) {
        return jdbc.query("SELECT " + SESSION_COLUMNS + " FROM class_sessions WHERE section_id = ? AND session_date = ? FOR UPDATE",
                (rs, i) -> row(rs), sectionId, date).stream().findFirst();
    }

    public Optional<SessionRow> lockSession(UUID sessionId) {
        return jdbc.query("SELECT " + SESSION_COLUMNS + " FROM class_sessions WHERE session_id = ? FOR UPDATE",
                (rs, i) -> row(rs), sessionId).stream().findFirst();
    }

    /** คาบอื่นในห้องนี้วันนี้ที่ยังไม่จบและเวลาทับกัน → คืนชื่อวิชา-กลุ่ม (ไว้บอกผู้ใช้) */
    public Optional<String> roomConflict(UUID roomId, LocalDate date, LocalTime from, LocalTime to, UUID exceptSection) {
        return jdbc.queryForList("""
                SELECT c.course_code || '-' || s.section_code
                FROM class_sessions cs
                JOIN sections s ON s.section_id = cs.section_id
                JOIN courses c ON c.course_id = s.course_id
                WHERE cs.room_id = ? AND cs.session_date = ? AND cs.section_id <> ?
                  AND cs.session_status IN ('scheduled', 'in_progress')
                  AND cs.start_time < ? AND cs.end_time > ?
                ORDER BY cs.start_time LIMIT 1
                """, String.class, roomId, date, exceptSection, to, from).stream().findFirst();
    }

    /** วันนี้ไม่มีคาบตามตาราง → คาบเพิ่ม (is_extra) · regenerateSessions ไม่แตะ */
    public UUID insertInProgress(UUID sectionId, UUID roomId, LocalDate date, Times t) {
        return jdbc.queryForObject("""
                INSERT INTO class_sessions (section_id, session_no, session_date, room_id, start_time, end_time,
                                            check_in_opens, check_in_closes, on_time_until, late_until, session_status,
                                            is_extra, is_overridden)
                SELECT ?, coalesce(max(session_no), 0) + 1, ?, ?, ?, ?, ?, ?, ?, ?, 'in_progress', true, true
                FROM class_sessions WHERE section_id = ?
                RETURNING session_id
                """, UUID.class, sectionId, date, roomId, t.start(), t.end(), t.opens(), t.closes(),
                t.onTimeUntil(), t.lateUntil(), sectionId);
    }

    /** ย้ายคาบวันนี้ (ยังไม่ถึงเวลา / ยกเลิก) มาเริ่มตอนนี้ → แก้รายคาบ (is_overridden) · ล้าง cancel_reason */
    public void moveToNow(UUID sessionId, UUID roomId, Times t) {
        jdbc.update("""
                UPDATE class_sessions SET room_id = ?, start_time = ?, end_time = ?, check_in_opens = ?,
                       check_in_closes = ?, on_time_until = ?, late_until = ?, session_status = 'in_progress',
                       cancel_reason = NULL, is_overridden = true
                WHERE session_id = ?
                """, roomId, t.start(), t.end(), t.opens(), t.closes(), t.onTimeUntil(), t.lateUntil(), sessionId);
    }

    /** ลบขาดที่ระบบใส่ (source = system) ของคาบ · ใช้ตอนเปิดคาบที่จบแล้วอีกครั้ง · คืนจำนวนแถว */
    public int deleteSystemAbsents(UUID sessionId) {
        return jdbc.update("DELETE FROM attendance_records WHERE session_id = ? AND source = 'system'", sessionId);
    }

    public Optional<String> roomCode(UUID roomId) {
        return jdbc.queryForList("SELECT room_code FROM rooms WHERE room_id = ?", String.class, roomId).stream().findFirst();
    }

    public void endAt(UUID sessionId, Times t) {
        jdbc.update("""
                UPDATE class_sessions SET end_time = ?, check_in_closes = ?, on_time_until = ?, late_until = ?,
                       session_status = 'completed'
                WHERE session_id = ?
                """, t.end(), t.closes(), t.onTimeUntil(), t.lateUntil(), sessionId);
    }

    private static SessionRow row(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new SessionRow(rs.getObject("session_id", UUID.class), rs.getObject("section_id", UUID.class),
                rs.getObject("room_id", UUID.class), rs.getDate("session_date").toLocalDate(),
                rs.getTime("start_time").toLocalTime(), rs.getTime("end_time").toLocalTime(),
                rs.getTime("check_in_opens").toLocalTime(), rs.getTime("on_time_until").toLocalTime(),
                rs.getTime("late_until").toLocalTime(), rs.getTime("check_in_closes").toLocalTime(),
                rs.getString("session_status"));
    }
}
