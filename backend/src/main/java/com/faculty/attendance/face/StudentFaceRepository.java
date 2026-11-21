package com.faculty.attendance.face;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * ตารางที่ใช้ (backend/db/01_schema.sql):
 *  student_profiles, consent_versions, consent_records (append-only), face_embeddings, v_current_consent
 *
 * กติกา PDPA (docs/database/database-design.md):
 *  - ไม่เก็บรูป เก็บเฉพาะ embedding ที่เข้ารหัสแล้ว (EmbeddingCipher)
 *  - ลงทะเบียนใหม่ = INSERT consent_records (granted) → DELETE ชุดเดิม → INSERT ชุดใหม่ ใน transaction เดียว
 *  - trigger ใน DB บังคับ: embedding ต้องอ้าง consent ที่ granted ของคนเดียวกัน และไม่เกิน 5 ชุดต่อคน
 */
@Repository
public class StudentFaceRepository {

    private static final ZoneId BANGKOK = ZoneId.of("Asia/Bangkok");

    private final JdbcTemplate jdbc;

    public StudentFaceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean studentExists(UUID studentId) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM student_profiles WHERE student_id = ?",
                Integer.class, studentId);
        return n != null && n > 0;
    }

    /** ฉบับข้อความยินยอมที่มีผลอยู่ (consent_versions.is_current) */
    public Optional<String> currentConsentVersion() {
        return jdbc.queryForList("SELECT version FROM consent_versions WHERE is_current", String.class)
                .stream().findFirst();
    }

    /** หลักฐานการให้ความยินยอม (append-only) คืน consent_id ไว้ผูกกับ embedding */
    public UUID insertGrantedConsent(UUID studentId, String version, boolean ageConfirmed, String ip,
                                     String userAgent) {
        return jdbc.queryForObject("""
                INSERT INTO consent_records (student_id, version, action, age_confirmed, ip_address, user_agent)
                VALUES (?, ?, 'granted', ?, ?::inet, ?)
                RETURNING consent_id
                """, UUID.class, studentId, version, ageConfirmed, ip, truncate(userAgent, 255));
    }

    /** ล็อกแถวนักศึกษาใน transaction ปัจจุบัน กันลงทะเบียน / ถอนพร้อมกันหลาย request */
    public void lockStudent(UUID studentId) {
        jdbc.queryForList("SELECT 1 FROM student_profiles WHERE student_id = ? FOR UPDATE", studentId);
    }

    /** แถวล่าสุดใน consent_records ของนักศึกษา (= v_current_consent) ใช้ตอนถอนความยินยอม */
    public record LatestConsent(UUID consentId, String version, String action, boolean ageConfirmed) {}

    public Optional<LatestConsent> latestConsent(UUID studentId) {
        return jdbc.query("""
                SELECT consent_id, version, action, age_confirmed
                FROM consent_records
                WHERE student_id = ?
                ORDER BY created_at DESC
                LIMIT 1
                """, (rs, i) -> new LatestConsent(rs.getObject("consent_id", UUID.class), rs.getString("version"),
                        rs.getString("action"), rs.getBoolean("age_confirmed")), studentId)
                .stream().findFirst();
    }

    /** B7 หลักฐานการถอน (append-only) อ้างฉบับเดียวกับที่ให้ไว้ · คืนเวลาที่ถอน */
    public OffsetDateTime insertWithdrawnConsent(UUID studentId, String version, boolean ageConfirmed, String ip,
                                                 String userAgent) {
        return jdbc.queryForObject("""
                INSERT INTO consent_records (student_id, version, action, age_confirmed, ip_address, user_agent)
                VALUES (?, ?, 'withdrawn', ?, ?::inet, ?)
                RETURNING created_at
                """, OffsetDateTime.class, studentId, version, ageConfirmed, ip, truncate(userAgent, 255));
    }

    /** หลักฐานการลบ (D7): ถอนเอง = ลบทันที → due_at = completed_at = now() */
    public void insertCompletedDeletionJob(UUID studentId, int deletedCount) {
        jdbc.update("""
                INSERT INTO data_deletion_jobs (student_id, reason, requested_at, due_at, completed_at, deleted_count)
                VALUES (?, 'withdrawn', now(), now(), now(), ?)
                """, studentId, deletedCount);
    }

    /** ลบจริง (ไม่ใช่ปิดใช้งาน) ตามข้อความยินยอม v1 */
    public int deleteAll(UUID studentId) {
        return jdbc.update("DELETE FROM face_embeddings WHERE student_id = ?", studentId);
    }

    public void insert(UUID studentId, UUID consentId, byte[] encryptedEmbedding, String model, String version) {
        jdbc.update("""
                INSERT INTO face_embeddings (student_id, consent_id, embedding, model_name, model_version)
                VALUES (?, ?, ?, ?, ?)
                """, studentId, consentId, encryptedEmbedding, model, version);
    }

    /**
     * สถานะใบหน้า + ความยินยอมล่าสุด (ตรรกะเดียวกับ v_face_registration แต่ไม่หายเมื่อยังไม่มี consent ฉบับปัจจุบัน)
     * registeredAt = เวลาที่บันทึก embedding ชุดปัจจุบัน
     */
    public record FaceStatusRow(boolean registered, LocalDateTime registeredAt, int activeFaces,
                                String consentStatus, String consentVersion, LocalDateTime consentedAt,
                                boolean needsReconsent) {}

    public FaceStatusRow findStatus(UUID studentId) {
        return jdbc.queryForObject("""
                SELECT count(fe.embedding_id)::int AS embedding_count,
                       max(fe.created_at)          AS registered_at,
                       cc.action                   AS consent_status,
                       cc.version                  AS consent_version,
                       cc.action_at                AS consent_changed_at,
                       COALESCE(cc.action = 'granted'
                                AND cc.version <> (SELECT version FROM consent_versions WHERE is_current),
                                false)             AS needs_reconsent
                FROM student_profiles sp
                LEFT JOIN face_embeddings fe ON fe.student_id = sp.student_id
                LEFT JOIN v_current_consent cc ON cc.student_id = sp.student_id
                WHERE sp.student_id = ?
                GROUP BY cc.action, cc.version, cc.action_at
                """, (rs, i) -> {
                    int count = rs.getInt("embedding_count");
                    return new FaceStatusRow(count > 0, local(rs.getObject("registered_at", OffsetDateTime.class)),
                            count, rs.getString("consent_status"), rs.getString("consent_version"),
                            local(rs.getObject("consent_changed_at", OffsetDateTime.class)),
                            rs.getBoolean("needs_reconsent"));
                }, studentId);
    }

    private static LocalDateTime local(OffsetDateTime t) {
        return t == null ? null : t.atZoneSameInstant(BANGKOK).toLocalDateTime();
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
