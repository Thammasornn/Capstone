package com.faculty.attendance.course;

import static com.faculty.attendance.common.Times.NOW_TH;

import com.faculty.attendance.common.ClassDays;
import com.faculty.attendance.course.CourseDtos.SectionDetail;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * ตาราง: courses, sections, section_class_days, section_lecturers, semesters, rooms, class_sessions
 * D1 ตารางเรียน/ห้อง/ช่วงเช็คชื่อ/สวิตช์กล้อง ผูกที่ sections · D2 class_sessions เป็นสำเนา ณ ตอนสร้างคาบ
 * Q5 ลบวิชา = soft delete (deleted_at)
 */
@Repository
public class CourseRepository {

    private final JdbcTemplate jdbc;

    public CourseRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---------- lookups ----------

    public record CourseRow(UUID courseId, String courseCode, String courseName) {}

    public Optional<CourseRow> findCourse(UUID courseId) {
        return jdbc.query("""
                SELECT course_id, course_code, course_name FROM courses
                WHERE course_id = ? AND deleted_at IS NULL
                """, (rs, i) -> new CourseRow(rs.getObject("course_id", UUID.class), rs.getString("course_code"),
                        rs.getString("course_name")), courseId).stream().findFirst();
    }

    /** ภาคเรียนที่กำลังเรียนอยู่ ถ้าไม่มี = ภาคถัดไปที่ใกล้ที่สุด */
    public Optional<UUID> currentOrNextSemester() {
        return jdbc.queryForList("""
                SELECT semester_id FROM semesters
                WHERE end_date >= (%s)::date
                ORDER BY start_date
                LIMIT 1
                """.formatted(NOW_TH), UUID.class).stream().findFirst();
    }

    public boolean courseCodeTaken(String code, UUID exceptCourseId) {
        return !jdbc.queryForList("""
                SELECT 1 FROM courses WHERE course_code = ? AND deleted_at IS NULL
                  AND (?::uuid IS NULL OR course_id <> ?::uuid)
                """, code, exceptCourseId, exceptCourseId).isEmpty();
    }

    public Optional<String> roomCode(UUID roomId) {
        return jdbc.queryForList("SELECT room_code FROM rooms WHERE room_id = ? AND is_active", String.class, roomId)
                .stream().findFirst();
    }

    public boolean isActiveLecturer(UUID userId) {
        return !jdbc.queryForList("SELECT 1 FROM users WHERE user_id = ? AND role = 'lecturer' AND is_active",
                userId).isEmpty();
    }

    public record SectionRef(UUID sectionId, UUID semesterId) {}

    public Optional<SectionRef> sectionOfCourse(UUID sectionId, UUID courseId) {
        return jdbc.query("""
                SELECT section_id, semester_id FROM sections
                WHERE section_id = ? AND course_id = ? AND deleted_at IS NULL
                """, (rs, i) -> new SectionRef(rs.getObject("section_id", UUID.class),
                        rs.getObject("semester_id", UUID.class)), sectionId, courseId).stream().findFirst();
    }

    public boolean sectionCodeTaken(UUID courseId, UUID semesterId, String code, UUID exceptSectionId) {
        return !jdbc.queryForList("""
                SELECT 1 FROM sections WHERE course_id = ? AND semester_id = ? AND section_code = ?
                  AND (?::uuid IS NULL OR section_id <> ?::uuid)
                """, courseId, semesterId, code, exceptSectionId, exceptSectionId).isEmpty();
    }

    /**
     * ห้องเดียวกัน ภาคเดียวกัน มีวันเรียนตรงกัน และเวลาทับกัน (ไม่นับ section ใน excludeIds)
     * @return "INT101-001" ของกลุ่มที่ชน
     */
    public Optional<String> roomClash(UUID semesterId, UUID roomId, Integer[] days, LocalTime start, LocalTime end,
                                      UUID[] excludeIds) {
        return jdbc.queryForList("""
                SELECT c.course_code || '-' || s.section_code
                FROM sections s
                JOIN courses c ON c.course_id = s.course_id AND c.deleted_at IS NULL
                WHERE s.deleted_at IS NULL AND s.semester_id = ? AND s.room_id = ?
                  AND s.start_time < ? AND ? < s.end_time
                  AND NOT (s.section_id = ANY (?::uuid[]))
                  AND EXISTS (SELECT 1 FROM section_class_days d
                              WHERE d.section_id = s.section_id AND d.day_of_week = ANY (?::int[]))
                LIMIT 1
                """, String.class, semesterId, roomId, end, start, excludeIds, days).stream().findFirst();
    }

    // ---------- writes ----------

    public UUID insertCourse(String code, String name, UUID createdBy) {
        return jdbc.queryForObject("""
                INSERT INTO courses (course_code, course_name, created_by) VALUES (?, ?, ?) RETURNING course_id
                """, UUID.class, code, name, createdBy);
    }

    public void updateCourse(UUID courseId, String code, String name) {
        jdbc.update("UPDATE courses SET course_code = ?, course_name = ? WHERE course_id = ?", code, name, courseId);
    }

    public record SectionValues(String sectionCode, UUID roomId, LocalTime startTime, LocalTime endTime,
                                LocalTime checkInOpens, LocalTime checkInCloses, int onTimeMinutes,
                                LocalTime lateUntil, boolean cameraEnabled) {}

    public UUID insertSection(UUID courseId, UUID semesterId, SectionValues v) {
        return jdbc.queryForObject("""
                INSERT INTO sections (course_id, semester_id, section_code, room_id, start_time, end_time,
                                      check_in_opens, check_in_closes, on_time_minutes, late_until,
                                      camera_attendance_enabled)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING section_id
                """, UUID.class, courseId, semesterId, v.sectionCode(), v.roomId(), v.startTime(), v.endTime(),
                v.checkInOpens(), v.checkInCloses(), v.onTimeMinutes(), v.lateUntil(), v.cameraEnabled());
    }

    public void updateSection(UUID sectionId, SectionValues v) {
        jdbc.update("""
                UPDATE sections SET section_code = ?, room_id = ?, start_time = ?, end_time = ?,
                       check_in_opens = ?, check_in_closes = ?, on_time_minutes = ?, late_until = ?,
                       camera_attendance_enabled = ?
                WHERE section_id = ?
                """, v.sectionCode(), v.roomId(), v.startTime(), v.endTime(), v.checkInOpens(), v.checkInCloses(),
                v.onTimeMinutes(), v.lateUntil(), v.cameraEnabled(), sectionId);
    }

    public void replaceClassDays(UUID sectionId, Integer[] days) {
        jdbc.update("DELETE FROM section_class_days WHERE section_id = ?", sectionId);
        jdbc.update("""
                INSERT INTO section_class_days (section_id, day_of_week)
                SELECT ?, d FROM unnest(?::int[]) AS d
                """, sectionId, days);
    }

    /** ผู้สอนหลักของ section (คนเดิมที่ไม่ใช่คนนี้ ถูกลดเป็นผู้สอนร่วม) */
    public void setPrimaryLecturer(UUID sectionId, UUID lecturerId) {
        jdbc.update("UPDATE section_lecturers SET is_primary = false WHERE section_id = ? AND lecturer_id <> ?",
                sectionId, lecturerId);
        jdbc.update("""
                INSERT INTO section_lecturers (section_id, lecturer_id, is_primary) VALUES (?, ?, true)
                ON CONFLICT (section_id, lecturer_id) DO UPDATE SET is_primary = true
                """, sectionId, lecturerId);
    }

    /**
     * D2: สร้างคาบใหม่ตามตารางเรียนปัจจุบัน ตั้งแต่ตอนนี้ถึงสิ้นภาค
     *  - ลบคาบที่ยังไม่เริ่ม (scheduled) และยังไม่มีข้อมูลอ้างอิง แล้วสร้างใหม่จากค่าใน sections
     *  - คาบที่เริ่มแล้ว / มีข้อมูลแล้ว ไม่แตะ (ผลเช็คชื่อย้อนหลังไม่เปลี่ยน)
     *  - เรียง session_no ใหม่ตามวันที่
     * @return จำนวนคาบที่สร้าง
     */
    public int regenerateSessions(UUID sectionId) {
        jdbc.update("""
                DELETE FROM class_sessions cs
                WHERE cs.section_id = ? AND cs.session_status = 'scheduled'
                  AND (cs.session_date + cs.start_time) > %s
                  AND NOT EXISTS (SELECT 1 FROM attendance_records a WHERE a.session_id = cs.session_id)
                  AND NOT EXISTS (SELECT 1 FROM occupancy_snapshots o WHERE o.session_id = cs.session_id)
                  AND NOT EXISTS (SELECT 1 FROM notifications n WHERE n.session_id = cs.session_id)
                """.formatted(NOW_TH), sectionId);
        int created = jdbc.update("""
                INSERT INTO class_sessions (section_id, session_no, session_date, room_id, start_time, end_time,
                                            check_in_opens, check_in_closes, on_time_until, late_until)
                SELECT s.section_id, 20000 + row_number() OVER (ORDER BY d), d::date, s.room_id,
                       s.start_time, s.end_time, s.check_in_opens, s.check_in_closes,
                       s.check_in_opens + make_interval(mins => s.on_time_minutes),
                       coalesce(s.late_until, s.check_in_closes)
                FROM sections s
                JOIN semesters se ON se.semester_id = s.semester_id
                CROSS JOIN LATERAL generate_series(greatest(se.start_date, (%1$s)::date)::timestamp,
                                                   se.end_date::timestamp, interval '1 day') AS d
                WHERE s.section_id = ? AND s.deleted_at IS NULL
                  AND extract(isodow FROM d)::int IN (SELECT day_of_week FROM section_class_days
                                                      WHERE section_id = s.section_id)
                  AND (d::date + s.start_time) > %1$s
                  AND NOT EXISTS (SELECT 1 FROM class_sessions x
                                  WHERE x.section_id = s.section_id AND x.session_date = d::date)
                """.formatted(NOW_TH), sectionId);
        // เรียงเลขคาบใหม่ 2 รอบ (ช่วงตัวเลขไม่ทับกัน จึงไม่ชน UNIQUE (section_id, session_no) ระหว่างทาง)
        jdbc.update("""
                UPDATE class_sessions cs SET session_no = 10000 + o.rn
                FROM (SELECT session_id,
                             row_number() OVER (ORDER BY session_date, start_time, session_id) AS rn
                      FROM class_sessions WHERE section_id = ?) o
                WHERE cs.session_id = o.session_id
                """, sectionId);
        jdbc.update("UPDATE class_sessions SET session_no = session_no - 10000 WHERE section_id = ?", sectionId);
        return created;
    }

    /** Q5 soft delete + ยกเลิกคาบที่ยังไม่เริ่ม · คืน deleted_at */
    public java.time.OffsetDateTime softDeleteCourse(UUID courseId) {
        var deletedAt = jdbc.queryForObject("""
                UPDATE courses SET deleted_at = now() WHERE course_id = ? AND deleted_at IS NULL
                RETURNING deleted_at
                """, java.time.OffsetDateTime.class, courseId);
        jdbc.update("""
                UPDATE class_sessions cs SET session_status = 'cancelled'
                FROM sections s
                WHERE s.section_id = cs.section_id AND s.course_id = ? AND s.deleted_at IS NULL
                  AND cs.session_status = 'scheduled' AND (cs.session_date + cs.start_time) > %s
                """.formatted(NOW_TH), courseId);
        jdbc.update("UPDATE sections SET deleted_at = ? WHERE course_id = ? AND deleted_at IS NULL",
                deletedAt, courseId);
        return deletedAt;
    }

    /** lecturer ลบวิชาได้เมื่อสอนทุก section ที่ยังไม่ถูกลบ */
    public boolean teachesAllSections(UUID courseId, UUID lecturerId) {
        Integer others = jdbc.queryForObject("""
                SELECT count(*) FROM sections s
                WHERE s.course_id = ? AND s.deleted_at IS NULL
                  AND NOT EXISTS (SELECT 1 FROM section_lecturers sl
                                  WHERE sl.section_id = s.section_id AND sl.lecturer_id = ?)
                """, Integer.class, courseId, lecturerId);
        return others != null && others == 0;
    }

    /** คืนค่าเดิม (null = ไม่พบ section) */
    public Boolean setCameraAttendance(UUID sectionId, boolean enabled) {
        return jdbc.query("""
                UPDATE sections s SET camera_attendance_enabled = ?
                FROM (SELECT section_id, camera_attendance_enabled AS old FROM sections
                      WHERE section_id = ? AND deleted_at IS NULL FOR UPDATE) o
                WHERE s.section_id = o.section_id
                RETURNING o.old
                """, (rs, i) -> rs.getBoolean("old"), enabled, sectionId).stream().findFirst().orElse(null);
    }

    // ---------- read ----------

    /** lecturerId = null -> ทุก section · ไม่ null -> เฉพาะ section ที่อาจารย์สอน */
    public List<SectionDetail> sections(UUID courseId, UUID lecturerId) {
        return jdbc.query("""
                SELECT sec.section_id, sec.section_code, se.term::text AS semester, se.academic_year,
                       pl.lecturer_id,
                       (SELECT string_agg(lu.first_name || ' ' || lu.last_name, ', '
                                          ORDER BY sl.is_primary DESC, lu.first_name)
                        FROM section_lecturers sl JOIN users lu ON lu.user_id = sl.lecturer_id
                        WHERE sl.section_id = sec.section_id) AS lecturer_name,
                       sec.room_id, r.room_code,
                       ARRAY(SELECT day_of_week::int FROM section_class_days d
                             WHERE d.section_id = sec.section_id ORDER BY 1) AS class_days,
                       sec.start_time, sec.end_time, sec.check_in_opens, sec.check_in_closes,
                       sec.on_time_minutes, sec.late_until, sec.camera_attendance_enabled
                FROM sections sec
                JOIN semesters se ON se.semester_id = sec.semester_id
                JOIN rooms r ON r.room_id = sec.room_id
                LEFT JOIN LATERAL (SELECT lecturer_id FROM section_lecturers
                                   WHERE section_id = sec.section_id
                                   ORDER BY is_primary DESC, created_at LIMIT 1) pl ON true
                WHERE sec.course_id = ? AND sec.deleted_at IS NULL
                  AND (?::uuid IS NULL OR EXISTS (SELECT 1 FROM section_lecturers sl
                        WHERE sl.section_id = sec.section_id AND sl.lecturer_id = ?::uuid))
                ORDER BY se.academic_year DESC, se.term DESC, sec.section_code
                """, (rs, i) -> section(rs), courseId, lecturerId, lecturerId);
    }

    static SectionDetail section(ResultSet rs) throws SQLException {
        Array days = rs.getArray("class_days");
        return new SectionDetail(rs.getObject("section_id", UUID.class), rs.getString("section_code"),
                rs.getString("semester"), rs.getObject("academic_year", Integer.class),
                rs.getObject("lecturer_id", UUID.class), rs.getString("lecturer_name"),
                rs.getObject("room_id", UUID.class), rs.getString("room_code"),
                ClassDays.fromIso(days == null ? null : (Object[]) days.getArray()),
                rs.getTime("start_time").toLocalTime(), rs.getTime("end_time").toLocalTime(),
                rs.getTime("check_in_opens").toLocalTime(), rs.getTime("check_in_closes").toLocalTime(),
                rs.getInt("on_time_minutes"),
                rs.getTime("late_until") == null ? null : rs.getTime("late_until").toLocalTime(),
                rs.getBoolean("camera_attendance_enabled"));
    }
}
