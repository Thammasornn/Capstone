package com.faculty.attendance.dashboard;

import com.faculty.attendance.common.ClassDays;
import com.faculty.attendance.dashboard.DashboardDtos.*;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * SQL ทั้งหมดของ Dashboard อยู่ที่นี่ที่เดียว (schema: backend/db/01_schema.sql)
 * ตาราง: courses -> sections (+ semesters, section_lecturers) -> class_sessions, enrollments,
 *        student_profiles + users, attendance_records, v_face_registration (สถานะลงทะเบียนใบหน้า)
 *
 * กติกา (docs/database/database-design.md):
 *  - นับเฉพาะนักศึกษา enrollments.status = 'active'
 *  - ไม่มีแถวใน attendance_records = absent
 *  - สรุประดับรายวิชา/รายคน นับเฉพาะคาบที่เริ่มแล้ว (เวลา Asia/Bangkok) และไม่ถูกยกเลิก
 *  - courses / sections ที่ soft delete (deleted_at) ไม่แสดง
 *  - checkOutTime ใน API = attendance_records.last_seen_at
 *
 * พารามิเตอร์ sectionId / lecturerId เป็น null ได้ (null = ไม่กรอง)
 *  lecturerId (= users.user_id) ใช้จำกัดให้อาจารย์เห็นเฉพาะ section ใน section_lecturers
 */
@Repository
public class DashboardRepository {

    private static final ZoneId BANGKOK = ZoneId.of("Asia/Bangkok");
    private static final String NOW_TH = "(now() AT TIME ZONE 'Asia/Bangkok')";
    /** ใช้กับ alias cs (class_sessions) */
    private static final String STARTED =
            "(cs.session_status <> 'cancelled' AND (cs.session_date + cs.start_time) <= " + NOW_TH + ")";
    private static final String SECTION_FILTER = "(?::uuid IS NULL OR sec.section_id = ?::uuid)";
    private static final String LECTURER_FILTER = """
            (?::uuid IS NULL OR EXISTS (SELECT 1 FROM section_lecturers sl
                                        WHERE sl.section_id = sec.section_id AND sl.lecturer_id = ?::uuid))""";
    private static final String ACTIVE_SECTION = "sec.deleted_at IS NULL";

    private final JdbcTemplate jdbc;

    public DashboardRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---------- lookups ----------

    public record CourseInfo(UUID id, String code, String name) {}

    public Optional<CourseInfo> findCourse(UUID courseId) {
        return jdbc.query("""
                SELECT course_id, course_code, course_name FROM courses
                WHERE course_id = ? AND deleted_at IS NULL
                """, (rs, i) -> new CourseInfo(rs.getObject("course_id", UUID.class), rs.getString("course_code"),
                        rs.getString("course_name")), courseId).stream().findFirst();
    }

    public boolean sectionInCourse(UUID sectionId, UUID courseId) {
        return !jdbc.queryForList("""
                SELECT 1 FROM sections WHERE section_id = ? AND course_id = ? AND deleted_at IS NULL LIMIT 1
                """, sectionId, courseId).isEmpty();
    }

    public record SessionInfo(UUID id, UUID sectionId, String sectionCode, UUID courseId, LocalDate sessionDate,
                              LocalTime startTime, LocalTime endTime, String status, String checkInOpens,
                              String onTimeUntil, String lateUntil, String checkInCloses) {}

    public Optional<SessionInfo> findSession(UUID sessionId) {
        return jdbc.query("""
                SELECT cs.session_id, cs.section_id, sec.section_code, sec.course_id, cs.session_date,
                       cs.start_time, cs.end_time, cs.session_status,
                       to_char(cs.check_in_opens, 'HH24:MI') AS check_in_opens,
                       to_char(cs.on_time_until, 'HH24:MI') AS on_time_until,
                       to_char(cs.late_until, 'HH24:MI') AS late_until,
                       to_char(cs.check_in_closes, 'HH24:MI') AS check_in_closes
                FROM class_sessions cs
                JOIN sections sec ON sec.section_id = cs.section_id
                JOIN courses c ON c.course_id = sec.course_id
                WHERE cs.session_id = ? AND sec.deleted_at IS NULL AND c.deleted_at IS NULL
                """,
                (rs, i) -> new SessionInfo(rs.getObject("session_id", UUID.class),
                        rs.getObject("section_id", UUID.class), rs.getString("section_code"),
                        rs.getObject("course_id", UUID.class), rs.getDate("session_date").toLocalDate(),
                        rs.getTime("start_time").toLocalTime(), rs.getTime("end_time").toLocalTime(),
                        rs.getString("session_status"), rs.getString("check_in_opens"),
                        rs.getString("on_time_until"), rs.getString("late_until"), rs.getString("check_in_closes")),
                sessionId).stream().findFirst();
    }

    public record StudentInfo(UUID id, String code, String name) {}

    /** นักศึกษาที่ลงทะเบียน (active) ใน section ใดก็ได้ของรายวิชานี้ */
    public Optional<StudentInfo> findEnrolledStudent(UUID courseId, UUID studentId) {
        return jdbc.query("""
                SELECT sp.student_id, sp.student_code, u.first_name || ' ' || u.last_name AS name
                FROM student_profiles sp
                JOIN users u ON u.user_id = sp.student_id
                WHERE sp.student_id = ?
                  AND EXISTS (SELECT 1
                              FROM enrollments e
                              JOIN sections sec ON sec.section_id = e.section_id
                              WHERE e.student_id = sp.student_id AND e.status = 'active'
                                AND sec.course_id = ? AND sec.deleted_at IS NULL)
                """,
                (rs, i) -> new StudentInfo(rs.getObject("student_id", UUID.class), rs.getString("student_code"),
                        rs.getString("name")),
                studentId, courseId).stream().findFirst();
    }

    // ---------- course / section list ----------

    public record CourseSectionRow(UUID courseId, String courseCode, String courseName, UUID sectionId,
                                   String sectionCode, String semester, Integer academicYear, String lecturerName,
                                   UUID lecturerId, UUID roomId, String roomCode, List<String> classDays,
                                   LocalTime startTime, LocalTime endTime, LocalTime checkInOpens,
                                   LocalTime checkInCloses, boolean cameraAttendanceEnabled) {}

    /**
     * lecturerId = เฉพาะ section ที่อาจารย์สอน, studentId = เฉพาะ section ที่นักศึกษาลงทะเบียน
     * semester = semesters.term ("1" | "2" | "3") · lecturerName = ผู้สอนทุกคน (คนหลักก่อน) คั่นด้วย ", "
     */
    public List<CourseSectionRow> courseSections(UUID lecturerId, UUID studentId) {
        return jdbc.query("""
                SELECT c.course_id, c.course_code, c.course_name,
                       sec.section_id, sec.section_code, se.term::text AS semester, se.academic_year,
                       (SELECT string_agg(lu.first_name || ' ' || lu.last_name, ', '
                                          ORDER BY sl.is_primary DESC, lu.first_name)
                        FROM section_lecturers sl JOIN users lu ON lu.user_id = sl.lecturer_id
                        WHERE sl.section_id = sec.section_id) AS lecturer_name,
                       (SELECT sl.lecturer_id FROM section_lecturers sl WHERE sl.section_id = sec.section_id
                        ORDER BY sl.is_primary DESC, sl.created_at LIMIT 1) AS lecturer_id,
                       sec.room_id, r.room_code,
                       ARRAY(SELECT d.day_of_week::int FROM section_class_days d
                             WHERE d.section_id = sec.section_id ORDER BY 1) AS class_days,
                       sec.start_time, sec.end_time, sec.check_in_opens, sec.check_in_closes,
                       sec.camera_attendance_enabled
                FROM courses c
                JOIN sections sec ON sec.course_id = c.course_id
                JOIN semesters se ON se.semester_id = sec.semester_id
                JOIN rooms r ON r.room_id = sec.room_id
                WHERE c.deleted_at IS NULL AND %s AND %s
                  AND (?::uuid IS NULL OR EXISTS (
                        SELECT 1 FROM enrollments e
                        WHERE e.section_id = sec.section_id AND e.student_id = ?::uuid AND e.status = 'active'))
                ORDER BY se.academic_year DESC, se.term DESC, c.course_code, sec.section_code
                """.formatted(ACTIVE_SECTION, LECTURER_FILTER),
                (rs, i) -> new CourseSectionRow(rs.getObject("course_id", UUID.class), rs.getString("course_code"),
                        rs.getString("course_name"), rs.getObject("section_id", UUID.class),
                        rs.getString("section_code"), rs.getString("semester"),
                        rs.getObject("academic_year", Integer.class), rs.getString("lecturer_name"),
                        rs.getObject("lecturer_id", UUID.class), rs.getObject("room_id", UUID.class),
                        rs.getString("room_code"),
                        ClassDays.fromIso((Object[]) rs.getArray("class_days").getArray()),
                        rs.getTime("start_time").toLocalTime(), rs.getTime("end_time").toLocalTime(),
                        rs.getTime("check_in_opens").toLocalTime(), rs.getTime("check_in_closes").toLocalTime(),
                        rs.getBoolean("camera_attendance_enabled")),
                lecturerId, lecturerId, studentId, studentId);
    }

    // ---------- summaries ----------

    public record CourseAgg(int sessions, int students, StatusCounts counts) {}

    /** ทุกคู่ (คาบที่เริ่มแล้ว x นักศึกษา active ใน section ของคาบนั้น) */
    public CourseAgg courseSummary(UUID courseId, UUID sectionId, UUID lecturerId) {
        return jdbc.queryForObject("""
                SELECT count(DISTINCT cs.session_id)                        AS sessions,
                       count(DISTINCT e.student_id)                         AS students,
                       count(*) FILTER (WHERE a.status = 'on_time')         AS on_time,
                       count(*) FILTER (WHERE a.status = 'late')            AS late,
                       count(*) FILTER (WHERE a.status IS NULL OR a.status = 'absent') AS absent
                FROM sections sec
                JOIN class_sessions cs ON cs.section_id = sec.section_id
                JOIN enrollments e ON e.section_id = sec.section_id AND e.status = 'active'
                LEFT JOIN attendance_records a ON a.session_id = cs.session_id AND a.student_id = e.student_id
                WHERE sec.course_id = ? AND %s AND %s AND %s AND %s
                """.formatted(ACTIVE_SECTION, SECTION_FILTER, LECTURER_FILTER, STARTED),
                (rs, i) -> new CourseAgg(rs.getInt("sessions"), rs.getInt("students"),
                        new StatusCounts(rs.getInt("on_time"), rs.getInt("late"), rs.getInt("absent"))),
                courseId, sectionId, sectionId, lecturerId, lecturerId);
    }

    /** ทุกคาบของรายวิชา (รวมคาบที่ยังไม่เริ่ม / ยกเลิก) พร้อมตัวเลขต่อคาบ */
    public List<SessionItem> sessionList(UUID courseId, UUID sectionId, UUID lecturerId) {
        return jdbc.query("""
                SELECT cs.session_id, sec.section_id, sec.section_code, cs.session_date, cs.start_time,
                       cs.end_time, cs.session_status, (%s) AS started,
                       count(e.student_id)                                   AS total,
                       count(*) FILTER (WHERE a.status = 'on_time')          AS on_time,
                       count(*) FILTER (WHERE a.status = 'late')             AS late,
                       count(*) FILTER (WHERE e.student_id IS NOT NULL
                                          AND (a.status IS NULL OR a.status = 'absent')) AS absent
                FROM sections sec
                JOIN class_sessions cs ON cs.section_id = sec.section_id
                LEFT JOIN enrollments e ON e.section_id = sec.section_id AND e.status = 'active'
                LEFT JOIN attendance_records a ON a.session_id = cs.session_id AND a.student_id = e.student_id
                WHERE sec.course_id = ? AND %s AND %s AND %s
                GROUP BY cs.session_id, sec.section_id, sec.section_code
                ORDER BY cs.session_date, cs.start_time, cs.session_id
                """.formatted(STARTED, ACTIVE_SECTION, SECTION_FILTER, LECTURER_FILTER),
                (rs, i) -> {
                    boolean started = rs.getBoolean("started");
                    var counts = started
                            ? new StatusCounts(rs.getInt("on_time"), rs.getInt("late"), rs.getInt("absent"))
                            : new StatusCounts(0, 0, 0);
                    return new SessionItem(rs.getObject("session_id", UUID.class),
                            rs.getObject("section_id", UUID.class), rs.getString("section_code"),
                            rs.getDate("session_date").toLocalDate(), rs.getTime("start_time").toLocalTime(),
                            rs.getTime("end_time").toLocalTime(), rs.getString("session_status"), started,
                            rs.getInt("total"), counts.onTime(), counts.late(), counts.absent(),
                            counts.attendanceRate());
                },
                courseId, sectionId, sectionId, lecturerId, lecturerId);
    }

    /** 1 แถวต่อนักศึกษา รวมสถานะลงทะเบียนใบหน้า (v_face_registration.face_registered) */
    public List<StudentSummaryRow> studentSummaries(UUID courseId, UUID sectionId, UUID lecturerId) {
        return jdbc.query("""
                SELECT sp.student_id, sp.student_code, u.first_name || ' ' || u.last_name AS name,
                       count(*) FILTER (WHERE a.status = 'on_time') AS on_time,
                       count(*) FILTER (WHERE a.status = 'late')    AS late,
                       count(*) FILTER (WHERE cs.session_id IS NOT NULL
                                          AND (a.status IS NULL OR a.status = 'absent')) AS absent,
                       COALESCE(fr.face_registered, false) AS face_registered
                FROM sections sec
                JOIN enrollments e ON e.section_id = sec.section_id AND e.status = 'active'
                JOIN student_profiles sp ON sp.student_id = e.student_id
                JOIN users u ON u.user_id = sp.student_id
                LEFT JOIN v_face_registration fr ON fr.student_id = sp.student_id
                LEFT JOIN class_sessions cs ON cs.section_id = sec.section_id AND %s
                LEFT JOIN attendance_records a ON a.session_id = cs.session_id AND a.student_id = sp.student_id
                WHERE sec.course_id = ? AND %s AND %s AND %s
                GROUP BY sp.student_id, sp.student_code, u.first_name, u.last_name, fr.face_registered
                ORDER BY sp.student_code
                """.formatted(STARTED, ACTIVE_SECTION, SECTION_FILTER, LECTURER_FILTER),
                (rs, i) -> {
                    var c = new StatusCounts(rs.getInt("on_time"), rs.getInt("late"), rs.getInt("absent"));
                    return new StudentSummaryRow(rs.getObject("student_id", UUID.class),
                            rs.getString("student_code"), rs.getString("name"), c.onTime(), c.late(), c.absent(),
                            c.attendanceRate(), rs.getBoolean("face_registered"));
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
                JOIN enrollments e ON e.section_id = cs.section_id AND e.status = 'active'
                LEFT JOIN attendance_records a ON a.session_id = cs.session_id AND a.student_id = e.student_id
                WHERE cs.session_id = ?
                """,
                (rs, i) -> new StatusCounts(rs.getInt("on_time"), rs.getInt("late"), rs.getInt("absent")),
                sessionId);
    }

    // ---------- detail lists ----------

    /** รายชื่อทั้งคาบ (รวมคนขาด) กรองตามสถานะได้ (status = null -> ทั้งหมด) */
    public List<AttendanceRow> sessionAttendance(UUID sessionId, String status) {
        return jdbc.query("""
                SELECT sp.student_id, sp.student_code, u.first_name || ' ' || u.last_name AS name,
                       a.check_in_time, a.last_seen_at, COALESCE(a.status, 'absent') AS status, a.source,
                       ai.method AS imported_from
                FROM class_sessions cs
                JOIN enrollments e ON e.section_id = cs.section_id AND e.status = 'active'
                JOIN student_profiles sp ON sp.student_id = e.student_id
                JOIN users u ON u.user_id = sp.student_id
                LEFT JOIN attendance_records a ON a.session_id = cs.session_id AND a.student_id = sp.student_id
                LEFT JOIN attendance_imports ai ON ai.import_id = a.import_id
                WHERE cs.session_id = ?
                  AND (?::text IS NULL OR COALESCE(a.status, 'absent') = ?::text)
                ORDER BY sp.student_code
                """,
                (rs, i) -> new AttendanceRow(rs.getObject("student_id", UUID.class), rs.getString("student_code"),
                        rs.getString("name"), ts(rs, "check_in_time"), ts(rs, "last_seen_at"),
                        rs.getString("status"), rs.getString("source"), rs.getString("imported_from")),
                sessionId, status, status);
    }

    /** ประวัติของนักศึกษาคนเดียวในรายวิชา ทุกคาบที่เริ่มแล้ว เรียงตามวันที่ */
    public List<StudentSessionRow> studentAttendance(UUID courseId, UUID studentId) {
        return jdbc.query("""
                SELECT cs.session_id, cs.session_date, a.check_in_time, a.last_seen_at,
                       COALESCE(a.status, 'absent') AS status
                FROM sections sec
                JOIN enrollments e ON e.section_id = sec.section_id
                     AND e.student_id = ? AND e.status = 'active'
                JOIN class_sessions cs ON cs.section_id = sec.section_id
                LEFT JOIN attendance_records a ON a.session_id = cs.session_id AND a.student_id = e.student_id
                WHERE sec.course_id = ? AND %s AND %s
                ORDER BY cs.session_date, cs.start_time, cs.session_id
                """.formatted(ACTIVE_SECTION, STARTED),
                (rs, i) -> new StudentSessionRow(rs.getObject("session_id", UUID.class),
                        rs.getDate("session_date").toLocalDate(), ts(rs, "check_in_time"),
                        ts(rs, "last_seen_at"), rs.getString("status")),
                studentId, courseId);
    }

    /** timestamptz -> เวลาไทยไม่มี timezone (ไม่ขึ้นกับ timezone ของ JVM) */
    private static LocalDateTime ts(ResultSet rs, String col) throws SQLException {
        OffsetDateTime t = rs.getObject(col, OffsetDateTime.class);
        return t == null ? null : t.atZoneSameInstant(BANGKOK).toLocalDateTime();
    }
}
