package com.faculty.attendance.enrollment;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * enrollments (นักศึกษาในกลุ่มเรียน) + สร้างบัญชีนักศึกษาใหม่ (users + student_profiles)
 * CHECK ใน DB: status dropped ⇔ dropped_at IS NOT NULL · PK (section_id, student_id)
 */
@Repository
public class EnrollmentRepository {

    private final JdbcTemplate jdbc;

    public EnrollmentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record SectionInfo(UUID sectionId, String courseCode, String sectionCode) {}

    /** กลุ่มเรียนที่ยังไม่ถูกลบ · lock = FOR UPDATE (กันเพิ่ม / ถอนซ้อนกัน) */
    public Optional<SectionInfo> section(UUID sectionId, boolean lock) {
        return jdbc.query("""
                SELECT s.section_id, c.course_code, s.section_code
                FROM sections s JOIN courses c ON c.course_id = s.course_id AND c.deleted_at IS NULL
                WHERE s.section_id = ? AND s.deleted_at IS NULL
                """ + (lock ? "FOR UPDATE OF s" : ""), (rs, i) -> new SectionInfo(rs.getObject("section_id", UUID.class),
                rs.getString("course_code"), rs.getString("section_code")), sectionId).stream().findFirst();
    }

    public record EnrolledRow(UUID studentId, String studentCode, String title, String firstName, String lastName,
                              String email, boolean accountActive, String status, OffsetDateTime enrolledAt,
                              OffsetDateTime droppedAt, boolean faceRegistered, String consentStatus) {}

    /** รายชื่อในกลุ่มเรียน (ทุกสถานะ) เรียงตามรหัส */
    public List<EnrolledRow> list(UUID sectionId) {
        return jdbc.query("""
                SELECT sp.student_id, sp.student_code, u.title, u.first_name, u.last_name, u.email, u.is_active,
                       e.status, e.enrolled_at, e.dropped_at,
                       EXISTS (SELECT 1 FROM face_embeddings fe WHERE fe.student_id = sp.student_id) AS face_registered,
                       cc.action AS consent_status
                FROM enrollments e
                JOIN student_profiles sp ON sp.student_id = e.student_id
                JOIN users u ON u.user_id = sp.student_id
                LEFT JOIN v_current_consent cc ON cc.student_id = sp.student_id
                WHERE e.section_id = ?
                ORDER BY sp.student_code
                """, (rs, i) -> new EnrolledRow(rs.getObject("student_id", UUID.class), rs.getString("student_code"),
                        rs.getString("title"), rs.getString("first_name"), rs.getString("last_name"),
                        rs.getString("email"), rs.getBoolean("is_active"), rs.getString("status"),
                        rs.getObject("enrolled_at", OffsetDateTime.class),
                        rs.getObject("dropped_at", OffsetDateTime.class), rs.getBoolean("face_registered"),
                        rs.getString("consent_status")), sectionId);
    }

    /** บัญชีนักศึกษาที่มีอยู่ + สถานะในกลุ่มนี้ (enrollmentStatus = null คือยังไม่เคยอยู่ในกลุ่ม) */
    public record StudentRef(UUID studentId, String studentCode, String email, String fullName, boolean accountActive,
                             String enrollmentStatus) {}

    public Map<String, StudentRef> studentsByCode(UUID sectionId, Collection<String> codes) {
        Map<String, StudentRef> out = new HashMap<>();
        if (codes.isEmpty()) return out;
        jdbc.query("""
                SELECT sp.student_id, sp.student_code, u.email, u.first_name || ' ' || u.last_name AS name,
                       u.is_active, e.status AS enrollment_status
                FROM student_profiles sp
                JOIN users u ON u.user_id = sp.student_id
                LEFT JOIN enrollments e ON e.student_id = sp.student_id AND e.section_id = ?
                WHERE sp.student_code = ANY (?)
                """, rs -> {
            out.put(rs.getString("student_code"), new StudentRef(rs.getObject("student_id", UUID.class),
                    rs.getString("student_code"), rs.getString("email"), rs.getString("name"),
                    rs.getBoolean("is_active"), rs.getString("enrollment_status")));
        }, sectionId, (Object) codes.toArray(String[]::new));
        return out;
    }

    /** อีเมลที่มีผู้ใช้แล้ว (ทุก role) → role · ใช้ตรวจก่อนสร้างบัญชีใหม่ */
    public Map<String, String> rolesByEmail(Collection<String> emails) {
        Map<String, String> out = new HashMap<>();
        if (emails.isEmpty()) return out;
        jdbc.query("SELECT email, role FROM users WHERE email = ANY (?)",
                rs -> { out.put(rs.getString("email"), rs.getString("role")); },
                (Object) emails.toArray(String[]::new));
        return out;
    }

    /** บัญชีนักศึกษาใหม่ (users + student_profiles) · passwordHash = BCrypt */
    public UUID createStudent(String studentCode, String email, String title, String firstName, String lastName,
                              String passwordHash) {
        UUID id = jdbc.queryForObject("""
                INSERT INTO users (email, password_hash, title, first_name, last_name, role)
                VALUES (?, ?, ?, ?, ?, 'student') RETURNING user_id
                """, UUID.class, email, passwordHash, title, firstName, lastName);
        jdbc.update("INSERT INTO student_profiles (student_id, student_code) VALUES (?, ?)", id, studentCode);
        return id;
    }

    /** เพิ่มเข้ากลุ่ม (enrolled_at = now()) · คืน false ถ้ามีแถวอยู่แล้ว */
    public boolean insert(UUID sectionId, UUID studentId) {
        return jdbc.update("""
                INSERT INTO enrollments (section_id, student_id, status, enrolled_at)
                VALUES (?, ?, 'active', now()) ON CONFLICT (section_id, student_id) DO NOTHING
                """, sectionId, studentId) == 1;
    }

    /** dropped → active อีกครั้ง · enrolled_at = now() (คาบที่ผ่านมาระหว่างถอนไม่ถูกลงขาดย้อนหลัง) */
    public boolean reactivate(UUID sectionId, UUID studentId) {
        return jdbc.update("""
                UPDATE enrollments SET status = 'active', enrolled_at = now(), dropped_at = NULL
                WHERE section_id = ? AND student_id = ? AND status = 'dropped'
                """, sectionId, studentId) == 1;
    }

    /** ถอน (soft) · คืน dropped_at หรือ empty ถ้าไม่ได้ active อยู่ */
    public Optional<OffsetDateTime> drop(UUID sectionId, UUID studentId) {
        return jdbc.query("""
                UPDATE enrollments SET status = 'dropped', dropped_at = now()
                WHERE section_id = ? AND student_id = ? AND status = 'active'
                RETURNING dropped_at
                """, (rs, i) -> rs.getObject("dropped_at", OffsetDateTime.class), sectionId, studentId)
                .stream().findFirst();
    }

    public Optional<String> enrollmentStatus(UUID sectionId, UUID studentId) {
        return jdbc.queryForList("SELECT status FROM enrollments WHERE section_id = ? AND student_id = ? FOR UPDATE",
                String.class, sectionId, studentId).stream().findFirst();
    }

    /** ผลเช็คชื่อของนักศึกษาในคาบของกลุ่มนี้ (เก็บไว้เมื่อถอน) */
    public int attendanceCount(UUID sectionId, UUID studentId) {
        Integer n = jdbc.queryForObject("""
                SELECT count(*) FROM attendance_records a JOIN class_sessions cs ON cs.session_id = a.session_id
                WHERE cs.section_id = ? AND a.student_id = ?
                """, Integer.class, sectionId, studentId);
        return n == null ? 0 : n;
    }
}
