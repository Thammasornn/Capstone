package com.faculty.attendance.auth;

import java.util.Arrays;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * GET /api/test-accounts — รายชื่อบัญชีใน DB ให้หน้า Login แสดงเป็นปุ่มกดกรอกอีเมล (local / uat เท่านั้น)
 *
 * เปิดด้วย app.test-accounts.enabled=true (env TEST_ACCOUNTS_ENABLED) · ค่าเริ่มต้นปิด = ไม่มี endpoint นี้ (404)
 * ไม่ต้องใช้ token (ใช้ก่อน login) จึงห้ามเปิดใน Production — ไม่คืนรหัสผ่าน / hash / ข้อมูลใบหน้า
 * สถานะที่คืนให้ (ใบหน้า, ความยินยอม, จำนวนวิชา) ใช้จัดกลุ่มบนหน้า Login เท่านั้น
 */
@RestController
@ConditionalOnProperty(name = "app.test-accounts.enabled", havingValue = "true")
public class TestAccountsController {

    private static final Logger log = LoggerFactory.getLogger(TestAccountsController.class);

    /**
     * @param activeCourses จำนวนวิชาที่ลงทะเบียนอยู่ในภาคเรียนปัจจุบัน (นักศึกษา)
     * @param teaching      รหัสวิชาที่สอนในภาคเรียนปัจจุบัน (อาจารย์)
     */
    public record TestAccount(String email, String role, String title, String firstName, String lastName,
                              boolean active, String studentCode, String academicStatus,
                              boolean faceRegistered, String consentStatus, boolean needsReconsent,
                              int activeCourses, List<String> teaching) {}

    /** ภาคเรียนปัจจุบัน = ภาคเรียนล่าสุดที่เริ่มแล้ว (เหมือน yearLevel ใน AuthRepository) */
    private static final String SQL = """
            WITH cur AS (
              SELECT semester_id FROM semesters WHERE start_date <= current_date
              ORDER BY start_date DESC LIMIT 1
            )
            SELECT u.email, u.role, u.title, u.first_name, u.last_name, u.is_active,
                   sp.student_code, sp.academic_status,
                   EXISTS (SELECT 1 FROM face_embeddings fe WHERE fe.student_id = u.user_id) AS face_registered,
                   cc.action AS consent_status,
                   COALESCE(cc.action = 'granted'
                            AND cc.version <> (SELECT version FROM consent_versions WHERE is_current),
                            false) AS needs_reconsent,
                   (SELECT count(*) FROM enrollments e
                      JOIN sections s ON s.section_id = e.section_id
                     WHERE e.student_id = u.user_id AND e.status = 'active'
                       AND s.semester_id IN (SELECT semester_id FROM cur))::int AS active_courses,
                   (SELECT string_agg(DISTINCT c.course_code, ',' ORDER BY c.course_code)
                      FROM section_lecturers sl
                      JOIN sections s ON s.section_id = sl.section_id
                      JOIN courses c ON c.course_id = s.course_id
                     WHERE sl.lecturer_id = u.user_id AND c.deleted_at IS NULL
                       AND s.semester_id IN (SELECT semester_id FROM cur)) AS teaching
            FROM users u
            LEFT JOIN student_profiles sp ON sp.student_id = u.user_id
            LEFT JOIN v_current_consent cc ON cc.student_id = u.user_id
            ORDER BY CASE u.role WHEN 'admin' THEN 0 WHEN 'lecturer' THEN 1 ELSE 2 END,
                     sp.student_code, u.email
            """;

    private final JdbcTemplate jdbc;

    public TestAccountsController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @EventListener(ApplicationReadyEvent.class)
    void warn() {
        log.warn("เปิด GET /api/test-accounts (app.test-accounts.enabled=true) — ใช้กับข้อมูลทดสอบเท่านั้น ห้ามเปิดใน Production");
    }

    @GetMapping("/api/test-accounts")
    public List<TestAccount> list() {
        return jdbc.query(SQL, (rs, i) -> {
            String teaching = rs.getString("teaching");
            return new TestAccount(rs.getString("email"), rs.getString("role"), rs.getString("title"),
                    rs.getString("first_name"), rs.getString("last_name"), rs.getBoolean("is_active"),
                    rs.getString("student_code"), rs.getString("academic_status"),
                    rs.getBoolean("face_registered"), rs.getString("consent_status"),
                    rs.getBoolean("needs_reconsent"), rs.getInt("active_courses"),
                    teaching == null ? List.of() : Arrays.asList(teaching.split(",")));
        });
    }
}
