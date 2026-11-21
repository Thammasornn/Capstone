package com.faculty.attendance.face;

import com.faculty.attendance.face.FaceDtos.EmbeddingItem;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** ตารางที่ใช้: students, student_face_embeddings (V9), biometric_enrollments, biometric_consents */
@Repository
public class StudentFaceRepository {

    /** ค่า biometric_enrollments.status เมื่อลงทะเบียนใบหน้าแล้ว (default เดิมคือ 'pending') */
    static final String STATUS_REGISTERED = "registered";

    private final JdbcTemplate jdbc;

    public StudentFaceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean studentExists(UUID studentId) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM students WHERE id = ?", Integer.class, studentId);
        return n != null && n > 0;
    }

    public void deactivateAll(UUID studentId) {
        jdbc.update("UPDATE student_face_embeddings SET is_active = FALSE, updated_at = now() "
                + "WHERE student_id = ? AND is_active", studentId);
    }

    public void insert(UUID studentId, EmbeddingItem item, String model, String version) {
        jdbc.update("""
                INSERT INTO student_face_embeddings
                  (student_id, embedding, model_name, model_version, quality_score)
                VALUES (?, ?::vector, ?, ?, ?)
                """,
                studentId, toVectorLiteral(item.embedding()), model, version, item.quality());
    }

    /** เก็บหลักฐานยินยอม (PDPA) ถ้ามีฉบับที่ยังมีผลอยู่แล้วจะไม่เพิ่มซ้ำ (unique index จาก V6) */
    public void recordConsent(UUID studentId, String textVersion, String ip) {
        jdbc.update("""
                INSERT INTO biometric_consents
                  (student_id, consent_given, consent_text_version, given_at, ip_address)
                SELECT ?, TRUE, ?, now(), ?
                WHERE NOT EXISTS (
                  SELECT 1 FROM biometric_consents
                  WHERE student_id = ? AND consent_given = TRUE AND revoked_at IS NULL)
                ON CONFLICT DO NOTHING
                """, studentId, textVersion, ip, studentId);
    }

    /** 1 นักศึกษามี 1 enrollment (unique index จาก V2) */
    public void upsertRegistered(UUID studentId) {
        jdbc.update("""
                INSERT INTO biometric_enrollments (student_id, status, registered_at, updated_at)
                VALUES (?, ?, now(), now())
                ON CONFLICT (student_id) DO UPDATE SET
                  status = EXCLUDED.status, registered_at = now(), updated_at = now()
                """, studentId, STATUS_REGISTERED);
    }

    public int countActiveFaces(UUID studentId) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM student_face_embeddings WHERE student_id = ? AND is_active",
                Integer.class, studentId);
        return n == null ? 0 : n;
    }

    public record EnrollmentRow(boolean registered, LocalDateTime registeredAt) {}

    public EnrollmentRow findEnrollment(UUID studentId) {
        List<EnrollmentRow> rows = jdbc.query(
                "SELECT status, registered_at FROM biometric_enrollments WHERE student_id = ?",
                (rs, i) -> new EnrollmentRow(STATUS_REGISTERED.equals(rs.getString("status")),
                        rs.getTimestamp("registered_at") == null ? null
                                : rs.getTimestamp("registered_at").toLocalDateTime()),
                studentId);
        return rows.isEmpty() ? new EnrollmentRow(false, null) : rows.get(0);
    }

    /** pgvector literal: "[0.1,0.2,...]" */
    static String toVectorLiteral(List<Float> v) {
        return v.stream().map(String::valueOf).collect(Collectors.joining(",", "[", "]"));
    }
}
