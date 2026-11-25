package com.faculty.attendance.attendance;

import com.faculty.attendance.course.CourseRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * "คาบเรียน" — จัดการคาบจริงรายคาบ (เลื่อน / ยกเลิก / คืนคาบ / คาบชดเชยหรือคาบพิเศษ) · คลาสออนไซต์
 * sections = แม่แบบตารางเรียน · class_sessions = คาบจริง · แก้รายคาบ → is_overridden = true
 * แล้ว CourseRepository.regenerateSessions จะไม่ลบ / สร้างซ้ำ (ดู docs/database/database-design.md)
 * CHECK ใน DB: end_time > start_time · check_in_opens <= on_time_until <= late_until <= check_in_closes ·
 * UNIQUE (section_id, session_date) · makeup_for_session_id <> session_id · is_extra ⇒ is_overridden
 * lock / reschedule / cancel / restore / insertExtra เรียกใน transaction เดียวกัน (Service ตรวจกติกาเอง)
 */
@Repository
public class ClassSessionRepository {

    private final JdbcTemplate jdbc;
    private final CourseRepository courses;

    public ClassSessionRepository(JdbcTemplate jdbc, CourseRepository courses) {
        this.jdbc = jdbc;
        this.courses = courses;
    }

    /** เวลา / ช่วงเช็คชื่อ / ห้อง ของคาบ (ค่าที่เขียนลง class_sessions) */
    public record SessionValues(LocalDate sessionDate, LocalTime startTime, LocalTime endTime,
                                LocalTime checkInOpens, LocalTime onTimeUntil, LocalTime lateUntil,
                                LocalTime checkInCloses, UUID roomId) {}

    /**
     * 1 คาบ + จำนวนผลเช็คชื่อ (noRecord = นักศึกษา active ที่ยังไม่มีแถว) ·
     * makeupForDate = วันที่ของคาบที่ชดเชย · semesterStart / semesterEnd = ช่วงภาคเรียนของกลุ่ม
     */
    public record SessionItem(UUID sessionId, UUID sectionId, int sessionNo, LocalDate sessionDate,
                              LocalDate originalDate, LocalTime startTime, LocalTime endTime,
                              LocalTime checkInOpens, LocalTime onTimeUntil, LocalTime lateUntil,
                              LocalTime checkInCloses, UUID roomId, String roomCode, String sessionStatus,
                              boolean isOverridden, boolean isExtra, UUID makeupForSessionId,
                              LocalDate makeupForDate, String cancelReason, int onTime, int late, int absent,
                              int noRecord, LocalDate semesterStart, LocalDate semesterEnd) {}

    private static final String SELECT = """
            SELECT cs.session_id, cs.section_id, cs.session_no, cs.session_date, cs.original_date,
                   cs.start_time, cs.end_time, cs.check_in_opens, cs.on_time_until, cs.late_until,
                   cs.check_in_closes, cs.room_id, r.room_code, cs.session_status, cs.is_overridden, cs.is_extra,
                   cs.makeup_for_session_id, mk.session_date AS makeup_for_date, cs.cancel_reason,
                   (SELECT count(*) FROM attendance_records a
                     WHERE a.session_id = cs.session_id AND a.status = 'on_time') AS on_time,
                   (SELECT count(*) FROM attendance_records a
                     WHERE a.session_id = cs.session_id AND a.status = 'late')    AS late,
                   (SELECT count(*) FROM attendance_records a
                     WHERE a.session_id = cs.session_id AND a.status = 'absent')  AS absent,
                   (SELECT count(*) FROM enrollments e
                     WHERE e.section_id = cs.section_id AND e.status = 'active'
                       AND NOT EXISTS (SELECT 1 FROM attendance_records a
                                       WHERE a.session_id = cs.session_id AND a.student_id = e.student_id))
                                                                                  AS no_record,
                   se.start_date AS semester_start, se.end_date AS semester_end
            FROM class_sessions cs
            JOIN sections s ON s.section_id = cs.section_id AND s.deleted_at IS NULL
            JOIN courses c ON c.course_id = s.course_id AND c.deleted_at IS NULL
            JOIN semesters se ON se.semester_id = s.semester_id
            JOIN rooms r ON r.room_id = cs.room_id
            LEFT JOIN class_sessions mk ON mk.session_id = cs.makeup_for_session_id
            """;

    /** ทุกคาบของกลุ่มเรียน (รวม cancelled / คาบเพิ่ม) เรียงตามวันที่ */
    public List<SessionItem> listBySection(UUID sectionId) {
        return jdbc.query(SELECT + """
                WHERE cs.section_id = ?
                ORDER BY cs.session_date, cs.start_time, cs.session_id
                """, (rs, i) -> item(rs), sectionId);
    }

    /** ล็อกแถว class_sessions (FOR UPDATE OF cs) · ไม่พบ / วิชาหรือกลุ่มถูกลบ = empty */
    public Optional<SessionItem> lock(UUID sessionId) {
        return jdbc.query(SELECT + """
                WHERE cs.session_id = ?
                FOR UPDATE OF cs
                """, (rs, i) -> item(rs), sessionId).stream().findFirst();
    }

    /** มีคาบอื่นของกลุ่มนี้ในวันนั้นไหม · นับทุกสถานะ (รวม cancelled) เพราะ UNIQUE (section_id, session_date) */
    public boolean existsOnDate(UUID sectionId, LocalDate date, UUID excludeSessionId) {
        return !jdbc.queryForList("""
                SELECT 1 FROM class_sessions
                WHERE section_id = ? AND session_date = ? AND (?::uuid IS NULL OR session_id <> ?::uuid)
                """, sectionId, date, excludeSessionId, excludeSessionId).isEmpty();
    }

    /** คาบอื่นในห้องเดียวกันวันเดียวกันที่เวลาทับกัน (ไม่นับ cancelled) → "courseCode-sectionCode" */
    public Optional<String> roomConflict(UUID roomId, LocalDate date, LocalTime start, LocalTime end,
                                         UUID excludeSessionId) {
        return jdbc.queryForList("""
                SELECT c.course_code || '-' || s.section_code
                FROM class_sessions cs
                JOIN sections s ON s.section_id = cs.section_id AND s.deleted_at IS NULL
                JOIN courses c ON c.course_id = s.course_id AND c.deleted_at IS NULL
                WHERE cs.room_id = ? AND cs.session_date = ? AND cs.session_status <> 'cancelled'
                  AND (?::uuid IS NULL OR cs.session_id <> ?::uuid)
                  AND cs.start_time < ? AND cs.end_time > ?
                ORDER BY cs.start_time LIMIT 1
                """, String.class, roomId, date, excludeSessionId, excludeSessionId, end, start)
                .stream().findFirst();
    }

    /** มีคาบชดเชย (ทุกสถานะ ยกเว้น cancelled) ที่ชี้มาที่คาบนี้ไหม */
    public boolean hasMakeup(UUID sessionId) {
        return !jdbc.queryForList("""
                SELECT 1 FROM class_sessions WHERE makeup_for_session_id = ? AND session_status <> 'cancelled' LIMIT 1
                """, sessionId).isEmpty();
    }

    public record SectionRef(UUID sectionId, LocalDate semesterStart, LocalDate semesterEnd) {}

    /** กลุ่มเรียนที่ยังไม่ถูกลบ + ช่วงภาคเรียน */
    public Optional<SectionRef> section(UUID sectionId) {
        return jdbc.query("""
                SELECT s.section_id, se.start_date, se.end_date
                FROM sections s
                JOIN courses c ON c.course_id = s.course_id AND c.deleted_at IS NULL
                JOIN semesters se ON se.semester_id = s.semester_id
                WHERE s.section_id = ? AND s.deleted_at IS NULL
                """, (rs, i) -> new SectionRef(rs.getObject("section_id", UUID.class),
                        rs.getDate("start_date").toLocalDate(), rs.getDate("end_date").toLocalDate()), sectionId)
                .stream().findFirst();
    }

    /** มีแถวใน attendance_records ไหม */
    public boolean hasAttendance(UUID sessionId) {
        return !jdbc.queryForList("SELECT 1 FROM attendance_records WHERE session_id = ? LIMIT 1", sessionId)
                .isEmpty();
    }

    /**
     * เลื่อน / แก้เวลา / ห้อง ของคาบ → is_overridden = true · original_date = วันที่เดิมเมื่อเปลี่ยนวันครั้งแรก
     * (เลื่อนซ้ำไม่ทับ original_date · เลื่อนกลับวันเดิมก็ยังเก็บไว้ กันสร้างซ้ำ) · เรียงเลขคาบใหม่เมื่อเปลี่ยนวัน
     */
    public void reschedule(UUID sessionId, SessionValues v) {
        UUID sectionId = jdbc.queryForObject("""
                UPDATE class_sessions cs SET
                    original_date   = CASE WHEN o.session_date <> ? THEN coalesce(cs.original_date, o.session_date)
                                           ELSE cs.original_date END,
                    session_date    = ?,
                    start_time      = ?, end_time = ?,
                    check_in_opens  = ?, on_time_until = ?, late_until = ?, check_in_closes = ?,
                    room_id         = ?,
                    is_overridden   = true
                FROM (SELECT session_id, session_date FROM class_sessions WHERE session_id = ?) o
                WHERE cs.session_id = o.session_id
                RETURNING cs.section_id
                """, UUID.class, v.sessionDate(), v.sessionDate(), v.startTime(), v.endTime(), v.checkInOpens(),
                v.onTimeUntil(), v.lateUntil(), v.checkInCloses(), v.roomId(), sessionId);
        courses.renumberSessions(sectionId);
    }

    /** ยกเลิกคาบ · ไม่ลบ attendance_records (Service ตัดสินว่ายกเลิกคาบที่มีผลได้ไหม) */
    public void cancel(UUID sessionId, String reason) {
        jdbc.update("""
                UPDATE class_sessions SET session_status = 'cancelled', cancel_reason = ?, is_overridden = true
                WHERE session_id = ?
                """, reason, sessionId);
    }

    /** คืนคาบที่ยกเลิก → scheduled (Service ตรวจว่าเป็นคาบในอนาคต) · ยังเป็น is_overridden */
    public void restore(UUID sessionId) {
        jdbc.update("""
                UPDATE class_sessions SET session_status = 'scheduled', cancel_reason = NULL
                WHERE session_id = ? AND session_status = 'cancelled'
                """, sessionId);
    }

    /**
     * คาบเพิ่ม (ชดเชย makeupForSessionId หรือคาบพิเศษ = null) → is_extra = is_overridden = true ·
     * session_no ชั่วคราว (เกินเลขสูงสุด + 20000 ไม่ชนช่วงที่ renumber ใช้) แล้วเรียงเลขคาบใหม่ทั้งกลุ่ม
     */
    public UUID insertExtra(UUID sectionId, SessionValues v, UUID makeupForSessionId) {
        UUID id = jdbc.queryForObject("""
                INSERT INTO class_sessions (section_id, session_no, session_date, room_id, start_time, end_time,
                                            check_in_opens, on_time_until, late_until, check_in_closes,
                                            is_extra, is_overridden, makeup_for_session_id)
                SELECT ?, coalesce(max(session_no), 0) + 20000, ?, ?, ?, ?, ?, ?, ?, ?, true, true, ?
                FROM class_sessions WHERE section_id = ?
                RETURNING session_id
                """, UUID.class, sectionId, v.sessionDate(), v.roomId(), v.startTime(), v.endTime(),
                v.checkInOpens(), v.onTimeUntil(), v.lateUntil(), v.checkInCloses(), makeupForSessionId, sectionId);
        courses.renumberSessions(sectionId);
        return id;
    }

    private static SessionItem item(ResultSet rs) throws SQLException {
        return new SessionItem(rs.getObject("session_id", UUID.class), rs.getObject("section_id", UUID.class),
                rs.getInt("session_no"), date(rs, "session_date"), date(rs, "original_date"),
                time(rs, "start_time"), time(rs, "end_time"), time(rs, "check_in_opens"),
                time(rs, "on_time_until"), time(rs, "late_until"), time(rs, "check_in_closes"),
                rs.getObject("room_id", UUID.class), rs.getString("room_code"), rs.getString("session_status"),
                rs.getBoolean("is_overridden"), rs.getBoolean("is_extra"),
                rs.getObject("makeup_for_session_id", UUID.class), date(rs, "makeup_for_date"),
                rs.getString("cancel_reason"), rs.getInt("on_time"), rs.getInt("late"), rs.getInt("absent"),
                rs.getInt("no_record"), date(rs, "semester_start"), date(rs, "semester_end"));
    }

    private static LocalDate date(ResultSet rs, String col) throws SQLException {
        java.sql.Date d = rs.getDate(col);
        return d == null ? null : d.toLocalDate();
    }

    private static LocalTime time(ResultSet rs, String col) throws SQLException {
        java.sql.Time t = rs.getTime(col);
        return t == null ? null : t.toLocalTime();
    }
}
