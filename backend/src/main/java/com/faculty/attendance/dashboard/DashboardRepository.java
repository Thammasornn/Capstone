package com.faculty.attendance.dashboard;

import com.faculty.attendance.dashboard.DashboardDtos.*;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * SQL ทั้งหมดของ Dashboard อยู่ที่นี่ที่เดียว
 * ตาราง: courses -> course_sections -> class_sessions, course_enrollments, students + users,
 *        attendance_records, biometric_enrollments (สถานะลงทะเบียนใบหน้า)
 *
 * กติกา:
 *  - นับเฉพาะนักศึกษา course_enrollments.status = 'enrolled'
 *  - ไม่มีแถวใน attendance_records = absent
 *  - สรุประดับรายวิชา/รายคน นับเฉพาะคาบที่เริ่มแล้ว (เวลา Asia/Bangkok)
 *
 * พารามิเตอร์ sectionId / lecturerId เป็น null ได้ (null = ไม่กรอง)
 *  lecturerId ใช้จำกัดให้อาจารย์เห็นเฉพาะ section ที่ตัวเองสอน
 */
@Repository
public class DashboardRepository {

    private static final String NOW_TH = "(now() AT TIME ZONE 'Asia/Bangkok')";
    /** ใช้กับ alias cs (class_sessions) */
    private static final String STARTED = "(cs.session_date + cs.start_time) <= " + NOW_TH;
    private static final String SECTION_FILTER = "(?::uuid IS NULL OR sec.id = ?::uuid)";
    private static final String LECTURER_FILTER = "(?::uuid IS NULL OR sec.lecturer_id = ?::uuid)";

    private final JdbcTemplate jdbc;

    public DashboardRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---------- lookups ----------

    public record CourseInfo(UUID id, String code, String name) {}

    public Optional<CourseInfo> findCourse(UUID courseId) {
        return jdbc.query("SELECT id, course_code, course_name FROM courses WHERE id = ?",
                (rs, i) -> new CourseInfo(rs.getObject("id", UUID.class), rs.getString("course_code"),
                        rs.getString("course_name")), courseId).stream().findFirst();
    }

    public boolean sectionInCourse(UUID sectionId, UUID courseId) {
        return !jdbc.queryForList("SELECT 1 FROM course_sections WHERE id = ? AND course_id = ? LIMIT 1",
                sectionId, courseId).isEmpty();
    }

    public record SessionInfo(UUID id, UUID sectionId, String sectionCode, UUID courseId, LocalDate sessionDate,
                              LocalTime startTime, LocalTime endTime, String status) {}

    public Optional<SessionInfo> findSession(UUID sessionId) {
        return jdbc.query("""
                SELECT cs.id, cs.section_id, sec.section_code, sec.course_id, cs.session_date,
                       cs.start_time, cs.end_time, cs.status
                FROM class_sessions cs
                JOIN course_sections sec ON sec.id = cs.section_id
                WHERE cs.id = ?
                """,
                (rs, i) -> new SessionInfo(rs.getObject("id", UUID.class), rs.getObject("section_id", UUID.class),
                        rs.getString("section_code"), rs.getObject("course_id", UUID.class),
                        rs.getDate("session_date").toLocalDate(), rs.getTime("start_time").toLocalTime(),
                        rs.getTime("end_time").toLocalTime(), rs.getString("status")),
                sessionId).stream().findFirst();
    }

    public record StudentInfo(UUID id, String code, String name) {}

    /** นักศึกษาที่ลงทะเบียน (enrolled) ใน section ใดก็ได้ของรายวิชานี้ */
    public Optional<StudentInfo> findEnrolledStudent(UUID courseId, UUID studentId) {
        return jdbc.query("""
                SELECT s.id, s.student_code, u.first_name || ' ' || u.last_name AS name
                FROM students s
                JOIN users u ON u.id = s.user_id
                WHERE s.id = ?
                  AND EXISTS (SELECT 1
                              FROM course_enrollments ce
                              JOIN course_sections sec ON sec.id = ce.section_id
                              WHERE ce.student_id = s.id AND ce.status = 'enrolled' AND sec.course_id = ?)
                """,
                (rs, i) -> new StudentInfo(rs.getObject("id", UUID.class), rs.getString("student_code"),
                        rs.getString("name")),
                studentId, courseId).stream().findFirst();
    }

    // ---------- course / section list ----------

    public record CourseSectionRow(UUID courseId, String courseCode, String courseName, UUID sectionId,
                                   String sectionCode, String semester, Integer academicYear, String lecturerName) {}

    /** lecturerId = เฉพาะ section ที่อาจารย์สอน, studentId = เฉพาะ section ที่นักศึกษาลงทะเบียน */
    public List<CourseSectionRow> courseSections(UUID lecturerId, UUID studentId) {
        return jdbc.query("""
                SELECT c.id AS course_id, c.course_code, c.course_name,
                       sec.id AS section_id, sec.section_code, sec.semester, sec.academic_year,
                       lu.first_name || ' ' || lu.last_name AS lecturer_name
                FROM courses c
                JOIN course_sections sec ON sec.course_id = c.id
                LEFT JOIN lecturers l ON l.id = sec.lecturer_id
                LEFT JOIN users lu ON lu.id = l.user_id
                WHERE %s
                  AND (?::uuid IS NULL OR EXISTS (
                        SELECT 1 FROM course_enrollments ce
                        WHERE ce.section_id = sec.id AND ce.student_id = ?::uuid AND ce.status = 'enrolled'))
                ORDER BY c.course_code, sec.section_code
                """.formatted(LECTURER_FILTER),
                (rs, i) -> new CourseSectionRow(rs.getObject("course_id", UUID.class), rs.getString("course_code"),
                        rs.getString("course_name"), rs.getObject("section_id", UUID.class),
                        rs.getString("section_code"), rs.getString("semester"),
                        rs.getObject("academic_year", Integer.class), rs.getString("lecturer_name")),
                lecturerId, lecturerId, studentId, studentId);
    }

    // ---------- summaries ----------

    public record CourseAgg(int sessions, int students, StatusCounts counts) {}

    /** ทุกคู่ (คาบที่เริ่มแล้ว x นักศึกษา enrolled ใน section ของคาบนั้น) */
    public CourseAgg courseSummary(UUID courseId, UUID sectionId, UUID lecturerId) {
        return jdbc.queryForObject("""
                SELECT count(DISTINCT cs.id)                                AS sessions,
                       count(DISTINCT ce.student_id)                        AS students,
                       count(*) FILTER (WHERE a.status = 'on_time')         AS on_time,
                       count(*) FILTER (WHERE a.status = 'late')            AS late,
                       count(*) FILTER (WHERE a.status IS NULL OR a.status = 'absent') AS absent
                FROM course_sections sec
                JOIN class_sessions cs ON cs.section_id = sec.id
                JOIN course_enrollments ce ON ce.section_id = sec.id AND ce.status = 'enrolled'
                LEFT JOIN attendance_records a ON a.session_id = cs.id AND a.student_id = ce.student_id
                WHERE sec.course_id = ? AND %s AND %s AND %s
                """.formatted(SECTION_FILTER, LECTURER_FILTER, STARTED),
                (rs, i) -> new CourseAgg(rs.getInt("sessions"), rs.getInt("students"),
                        new StatusCounts(rs.getInt("on_time"), rs.getInt("late"), rs.getInt("absent"))),
                courseId, sectionId, sectionId, lecturerId, lecturerId);
    }

    /** ทุกคาบของรายวิชา (รวมคาบที่ยังไม่เริ่ม) พร้อมตัวเลขต่อคาบ */
    public List<SessionItem> sessionList(UUID courseId, UUID sectionId, UUID lecturerId) {
        return jdbc.query("""
                SELECT cs.id, sec.id AS section_id, sec.section_code, cs.session_date, cs.start_time, cs.end_time,
                       cs.status, (%s) AS started,
                       count(ce.student_id)                                  AS total,
                       count(*) FILTER (WHERE a.status = 'on_time')          AS on_time,
                       count(*) FILTER (WHERE a.status = 'late')             AS late,
                       count(*) FILTER (WHERE ce.student_id IS NOT NULL
                                          AND (a.status IS NULL OR a.status = 'absent')) AS absent
                FROM course_sections sec
                JOIN class_sessions cs ON cs.section_id = sec.id
                LEFT JOIN course_enrollments ce ON ce.section_id = sec.id AND ce.status = 'enrolled'
                LEFT JOIN attendance_records a ON a.session_id = cs.id AND a.student_id = ce.student_id
                WHERE sec.course_id = ? AND %s AND %s
                GROUP BY cs.id, sec.id, sec.section_code
                ORDER BY cs.session_date, cs.start_time, cs.id
                """.formatted(STARTED, SECTION_FILTER, LECTURER_FILTER),
                (rs, i) -> {
                    boolean started = rs.getBoolean("started");
                    var counts = started
                            ? new StatusCounts(rs.getInt("on_time"), rs.getInt("late"), rs.getInt("absent"))
                            : new StatusCounts(0, 0, 0);
                    return new SessionItem(rs.getObject("id", UUID.class), rs.getObject("section_id", UUID.class),
                            rs.getString("section_code"), rs.getDate("session_date").toLocalDate(),
                            rs.getTime("start_time").toLocalTime(), rs.getTime("end_time").toLocalTime(),
                            rs.getString("status"), started, rs.getInt("total"),
                            counts.onTime(), counts.late(), counts.absent(), counts.attendanceRate());
                },
                courseId, sectionId, sectionId, lecturerId, lecturerId);
    }

    /** 1 แถวต่อนักศึกษา รวมสถานะลงทะเบียนใบหน้า (biometric_enrollments.status = 'registered') */
    public List<StudentSummaryRow> studentSummaries(UUID courseId, UUID sectionId, UUID lecturerId) {
        return jdbc.query("""
                SELECT s.id, s.student_code, u.first_name || ' ' || u.last_name AS name,
                       count(*) FILTER (WHERE a.status = 'on_time') AS on_time,
                       count(*) FILTER (WHERE a.status = 'late')    AS late,
                       count(*) FILTER (WHERE cs.id IS NOT NULL
                                          AND (a.status IS NULL OR a.status = 'absent')) AS absent,
                       COALESCE(be.status, 'pending') = 'registered' AS face_registered
                FROM course_sections sec
                JOIN course_enrollments ce ON ce.section_id = sec.id AND ce.status = 'enrolled'
                JOIN students s ON s.id = ce.student_id
                JOIN users u ON u.id = s.user_id
                LEFT JOIN biometric_enrollments be ON be.student_id = s.id
                LEFT JOIN class_sessions cs ON cs.section_id = sec.id AND %s
                LEFT JOIN attendance_records a ON a.session_id = cs.id AND a.student_id = s.id
                WHERE sec.course_id = ? AND %s AND %s
                GROUP BY s.id, s.student_code, u.first_name, u.last_name, be.status
                ORDER BY s.student_code
                """.formatted(STARTED, SECTION_FILTER, LECTURER_FILTER),
                (rs, i) -> {
                    var c = new StatusCounts(rs.getInt("on_time"), rs.getInt("late"), rs.getInt("absent"));
                    return new StudentSummaryRow(rs.getObject("id", UUID.class), rs.getString("student_code"),
                            rs.getString("name"), c.onTime(), c.late(), c.absent(), c.attendanceRate(),
                            rs.getBoolean("face_registered"));
                },
                courseId, sectionId, sectionId, lecturerId, lecturerId);
    }

    /** ระดับคาบ: ไม่กรองเวลา (ดูคาบที่กำลังเรียนอยู่ได้แบบ live) */
    public StatusCounts sessionSummary(UUID sessionId) {
        return jdbc.queryForObject("""
                SELECT count(*) FILTER (WHERE a.status = 'on_time')         AS on_time,
                       count(*) FILTER (WHERE a.status = 'late')            AS late,
                       count(*) FILTER (WHERE a.status IS NULL OR a.status = 'absent') AS absent
                FROM class_sessions cs
                JOIN course_enrollments ce ON ce.section_id = cs.section_id AND ce.status = 'enrolled'
                LEFT JOIN attendance_records a ON a.session_id = cs.id AND a.student_id = ce.student_id
                WHERE cs.id = ?
                """,
                (rs, i) -> new StatusCounts(rs.getInt("on_time"), rs.getInt("late"), rs.getInt("absent")),
                sessionId);
    }

    // ---------- detail lists ----------

    /** รายชื่อทั้งคาบ (รวมคนขาด) กรองตามสถานะได้ (status = null -> ทั้งหมด) */
    public List<AttendanceRow> sessionAttendance(UUID sessionId, String status) {
        return jdbc.query("""
                SELECT s.id, s.student_code, u.first_name || ' ' || u.last_name AS name,
                       a.check_in_time, a.check_out_time, COALESCE(a.status, 'absent') AS status
                FROM class_sessions cs
                JOIN course_enrollments ce ON ce.section_id = cs.section_id AND ce.status = 'enrolled'
                JOIN students s ON s.id = ce.student_id
                JOIN users u ON u.id = s.user_id
                LEFT JOIN attendance_records a ON a.session_id = cs.id AND a.student_id = s.id
                WHERE cs.id = ?
                  AND (?::text IS NULL OR COALESCE(a.status, 'absent') = ?::text)
                ORDER BY s.student_code
                """,
                (rs, i) -> new AttendanceRow(rs.getObject("id", UUID.class), rs.getString("student_code"),
                        rs.getString("name"), ts(rs, "check_in_time"), ts(rs, "check_out_time"),
                        rs.getString("status")),
                sessionId, status, status);
    }

    /** ประวัติของนักศึกษาคนเดียวในรายวิชา ทุกคาบที่เริ่มแล้ว เรียงตามวันที่ */
    public List<StudentSessionRow> studentAttendance(UUID courseId, UUID studentId) {
        return jdbc.query("""
                SELECT cs.id, cs.session_date, a.check_in_time, a.check_out_time,
                       COALESCE(a.status, 'absent') AS status
                FROM course_sections sec
                JOIN course_enrollments ce ON ce.section_id = sec.id
                     AND ce.student_id = ? AND ce.status = 'enrolled'
                JOIN class_sessions cs ON cs.section_id = sec.id
                LEFT JOIN attendance_records a ON a.session_id = cs.id AND a.student_id = ce.student_id
                WHERE sec.course_id = ? AND %s
                ORDER BY cs.session_date, cs.start_time, cs.id
                """.formatted(STARTED),
                (rs, i) -> new StudentSessionRow(rs.getObject("id", UUID.class),
                        rs.getDate("session_date").toLocalDate(), ts(rs, "check_in_time"),
                        ts(rs, "check_out_time"), rs.getString("status")),
                studentId, courseId);
    }

    private static LocalDateTime ts(ResultSet rs, String col) throws SQLException {
        Timestamp t = rs.getTimestamp(col);
        return t == null ? null : t.toLocalDateTime();
    }
}
