package com.faculty.attendance.attendance;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * นำเข้าผลเช็คชื่อจากไฟล์: attendance_imports (1 แถวต่อการนำเข้า) + attendance_import_rows (ทุกแถวในไฟล์)
 * เก็บเฉพาะข้อมูลที่อ่านได้ ไม่เก็บตัวไฟล์ · ใช้กับคลาสออนไซต์ (คาบใน class_sessions)
 * CHECK ใน DB: แถว import ต้องมี import_id · recorded_by / reason = NULL (อยู่ใน attendance_imports) ·
 * absent ⇔ check_in_time IS NULL · last_seen_at >= check_in_time
 * ลำดับความสำคัญ (Service ตัดสิน): manual > import > camera > system
 * lockSession / currentRecords(forUpdate) / upsertImported เรียกใน transaction เดียวกัน
 */
@Repository
public class AttendanceImportRepository {

    private final JdbcTemplate jdbc;

    public AttendanceImportRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---------- session / students ----------

    public record ImportSession(UUID sessionId, UUID sectionId, UUID roomId, String courseCode, String sectionCode,
                                LocalDate sessionDate, String sessionStatus, LocalTime startTime, LocalTime endTime,
                                LocalTime onTimeUntil, LocalTime lateUntil) {}

    private static final String SESSION_SQL = """
            SELECT cs.session_id, cs.section_id, cs.room_id, c.course_code, s.section_code, cs.session_date,
                   cs.session_status, cs.start_time, cs.end_time, cs.on_time_until, cs.late_until
            FROM class_sessions cs
            JOIN sections s ON s.section_id = cs.section_id AND s.deleted_at IS NULL
            JOIN courses c ON c.course_id = s.course_id AND c.deleted_at IS NULL
            WHERE cs.session_id = ?
            """;

    public Optional<ImportSession> findSession(UUID sessionId) {
        return jdbc.query(SESSION_SQL, (rs, i) -> session(rs), sessionId).stream().findFirst();
    }

    /** ล็อกแถว class_sessions (กันนำเข้าซ้อน / ชนกับ AttendanceJobs และการปรับเวลาคาบ) */
    public Optional<ImportSession> lockSession(UUID sessionId) {
        return jdbc.query(SESSION_SQL + "FOR UPDATE OF cs", (rs, i) -> session(rs), sessionId).stream().findFirst();
    }

    public record EnrolledStudent(UUID studentId, String studentCode, String fullName) {}

    /** นักศึกษา active ใน section → key = student_code · เรียงตามรหัส (ใช้ทำ template ด้วย) */
    public Map<String, EnrolledStudent> activeStudentsByCode(UUID sectionId) {
        Map<String, EnrolledStudent> out = new LinkedHashMap<>();
        jdbc.query("""
                SELECT sp.student_id, sp.student_code, u.first_name || ' ' || u.last_name AS name
                FROM enrollments e
                JOIN student_profiles sp ON sp.student_id = e.student_id
                JOIN users u ON u.user_id = sp.student_id
                WHERE e.section_id = ? AND e.status = 'active'
                ORDER BY sp.student_code
                """, rs -> {
            String code = rs.getString("student_code");
            out.put(code, new EnrolledStudent(rs.getObject("student_id", UUID.class), code, rs.getString("name")));
        }, sectionId);
        return out;
    }

    /** รหัสนักศึกษาที่มีในระบบ (ไว้แยก not_found กับ not_enrolled ตอนจับคู่ไม่ได้) */
    public java.util.Set<String> existingStudentCodes(java.util.Collection<String> codes) {
        if (codes.isEmpty()) return java.util.Set.of();
        return new java.util.HashSet<>(jdbc.queryForList(
                "SELECT student_code FROM student_profiles WHERE student_code = ANY (?)", String.class,
                (Object) codes.toArray(String[]::new)));
    }

    // ---------- attendance_records ----------

    public record CurrentRecord(UUID studentId, String source, String status, OffsetDateTime checkInTime,
                                UUID importId) {}

    /** ผลเช็คชื่อปัจจุบันของคาบ · key = student_id · forUpdate = ล็อกแถว (ตอน commit การนำเข้า) */
    public Map<UUID, CurrentRecord> currentRecords(UUID sessionId, boolean forUpdate) {
        Map<UUID, CurrentRecord> out = new LinkedHashMap<>();
        jdbc.query("""
                SELECT student_id, source, status, check_in_time, import_id
                FROM attendance_records WHERE session_id = ?
                """ + (forUpdate ? "FOR UPDATE" : ""), rs -> {
            UUID id = rs.getObject("student_id", UUID.class);
            out.put(id, new CurrentRecord(id, rs.getString("source"), rs.getString("status"),
                    rs.getObject("check_in_time", OffsetDateTime.class), rs.getObject("import_id", UUID.class)));
        }, sessionId);
        return out;
    }

    /**
     * เขียนผลนำเข้า 1 คน (insert หรือทับแถวเดิม) → source = import · ล้าง recorded_by / reason / match_score
     * last_seen_at ใช้สูตรเดียวกับ AttendanceRepository.upsertManual (ไม่ชน CHECK last_seen_at >= check_in_time)
     */
    public void upsertImported(UUID sessionId, UUID studentId, String status, OffsetDateTime checkInTime,
                               UUID importId) {
        jdbc.update("""
                INSERT INTO attendance_records (session_id, student_id, status, check_in_time, last_seen_at,
                                                source, import_id)
                VALUES (?, ?, ?, ?, NULL, 'import', ?)
                ON CONFLICT (session_id, student_id) DO UPDATE SET
                    status        = EXCLUDED.status,
                    check_in_time = EXCLUDED.check_in_time,
                    last_seen_at  = CASE WHEN EXCLUDED.status = 'absent' OR attendance_records.last_seen_at IS NULL
                                         THEN NULL
                                         ELSE greatest(attendance_records.last_seen_at, EXCLUDED.check_in_time) END,
                    source        = 'import',
                    import_id     = EXCLUDED.import_id,
                    recorded_by   = NULL,
                    reason        = NULL,
                    match_score   = NULL
                """, sessionId, studentId, status, checkInTime, importId);
    }

    // ---------- attendance_imports ----------

    public record PreviousImport(UUID importId, String fileName, OffsetDateTime importedAt, String importedByName) {}

    /** การนำเข้าก่อนหน้าของคาบนี้ที่ใช้ไฟล์เดียวกัน (ใหม่สุดก่อน) → ให้ preview เตือน */
    public Optional<PreviousImport> findImportBySha(UUID sessionId, String sha256) {
        return jdbc.query("""
                SELECT ai.import_id, ai.file_name, ai.imported_at, u.first_name || ' ' || u.last_name AS by_name
                FROM attendance_imports ai
                JOIN users u ON u.user_id = ai.imported_by
                WHERE ai.session_id = ? AND ai.file_sha256 = ?
                ORDER BY ai.imported_at DESC
                LIMIT 1
                """, (rs, i) -> new PreviousImport(rs.getObject("import_id", UUID.class), rs.getString("file_name"),
                        rs.getObject("imported_at", OffsetDateTime.class), rs.getString("by_name")),
                sessionId, sha256).stream().findFirst();
    }

    /** mode = template | mapped · method = template | teams | zoom | google_form | paper | other · mappingJson = null เมื่อ template */
    public record NewImport(UUID sessionId, String mode, String method, String fileName, String fileSha256,
                            String mappingJson, String reason, boolean overrideCamera, boolean overrideManual,
                            int totalRows, int insertedCount, int replacedCount, int skippedCount,
                            int conflictCount, int unmatchedCount, UUID importedBy) {}

    public UUID insertImport(NewImport in) {
        return jdbc.queryForObject("""
                INSERT INTO attendance_imports (session_id, mode, method, file_name, file_sha256, mapping, reason,
                                                override_camera, override_manual, total_rows, inserted_count,
                                                replaced_count, skipped_count, conflict_count, unmatched_count,
                                                imported_by)
                VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING import_id
                """, UUID.class, in.sessionId(), in.mode(), in.method(), in.fileName(), in.fileSha256(),
                in.mappingJson(), in.reason(), in.overrideCamera(), in.overrideManual(), in.totalRows(),
                in.insertedCount(), in.replacedCount(), in.skippedCount(), in.conflictCount(),
                in.unmatchedCount(), in.importedBy());
    }

    /**
     * 1 แถวจากไฟล์ · raw* = ค่าดิบ (ตัดให้ไม่เกิน 255 ที่นี่) · action = insert | replace | skip | conflict | unmatched ·
     * unmatchedReason = not_found | not_enrolled | duplicate (เฉพาะ unmatched) · applied = เขียนลง attendance_records จริง
     */
    public record NewRow(int rowNo, String rawIdentifier, String rawJoinTime, String rawStatus, UUID studentId,
                         String status, OffsetDateTime checkInTime, String action, String unmatchedReason,
                         boolean applied) {}

    /** batch INSERT (≤ 500 แถวตาม CHECK total_rows) */
    public void insertRows(UUID importId, List<NewRow> rows) {
        jdbc.batchUpdate("""
                INSERT INTO attendance_import_rows (import_id, row_no, raw_identifier, raw_join_time, raw_status,
                                                    student_id, status, check_in_time, action, unmatched_reason,
                                                    applied)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, rows, 500, (ps, r) -> {
            ps.setObject(1, importId);
            ps.setInt(2, r.rowNo());
            ps.setString(3, cut(r.rawIdentifier()));
            ps.setString(4, cut(r.rawJoinTime()));
            ps.setString(5, cut(r.rawStatus()));
            ps.setObject(6, r.studentId());
            ps.setString(7, r.status());
            ps.setObject(8, r.checkInTime());
            ps.setString(9, r.action());
            ps.setString(10, r.unmatchedReason());
            ps.setBoolean(11, r.applied());
        });
    }

    public record ImportSummary(UUID importId, String mode, String method, String fileName, String fileSha256,
                                String mappingJson, String reason, boolean overrideCamera, boolean overrideManual,
                                int totalRows, int insertedCount, int replacedCount, int skippedCount,
                                int conflictCount, int unmatchedCount, UUID importedBy, String importedByName,
                                OffsetDateTime importedAt) {}

    /** ประวัติการนำเข้าของคาบ เรียงใหม่ → เก่า */
    public List<ImportSummary> history(UUID sessionId) {
        return jdbc.query("""
                SELECT ai.*, ai.mapping::text AS mapping_json, u.first_name || ' ' || u.last_name AS by_name
                FROM attendance_imports ai
                JOIN users u ON u.user_id = ai.imported_by
                WHERE ai.session_id = ?
                ORDER BY ai.imported_at DESC, ai.import_id
                """, (rs, i) -> new ImportSummary(rs.getObject("import_id", UUID.class), rs.getString("mode"),
                        rs.getString("method"), rs.getString("file_name"), rs.getString("file_sha256"),
                        rs.getString("mapping_json"), rs.getString("reason"), rs.getBoolean("override_camera"),
                        rs.getBoolean("override_manual"), rs.getInt("total_rows"), rs.getInt("inserted_count"),
                        rs.getInt("replaced_count"), rs.getInt("skipped_count"), rs.getInt("conflict_count"),
                        rs.getInt("unmatched_count"), rs.getObject("imported_by", UUID.class),
                        rs.getString("by_name"), rs.getObject("imported_at", OffsetDateTime.class)),
                sessionId);
    }

    /** sessionId ของการนำเข้า (ใช้ตรวจสิทธิ์ก่อนอ่าน rows) */
    public Optional<UUID> sessionOfImport(UUID importId) {
        return jdbc.queryForList("SELECT session_id FROM attendance_imports WHERE import_id = ?", UUID.class, importId)
                .stream().findFirst();
    }

    public record ImportRow(int rowNo, String rawIdentifier, String rawJoinTime, String rawStatus, UUID studentId,
                            String studentCode, String studentName, String status, OffsetDateTime checkInTime,
                            String action, String unmatchedReason, boolean applied) {}

    /** ทุกแถวของการนำเข้า + รหัส / ชื่อนักศึกษา (null เมื่อจับคู่ไม่ได้) เรียงตาม row_no */
    public List<ImportRow> rows(UUID importId) {
        return jdbc.query("""
                SELECT r.row_no, r.raw_identifier, r.raw_join_time, r.raw_status, r.student_id, sp.student_code,
                       CASE WHEN u.user_id IS NULL THEN NULL ELSE u.first_name || ' ' || u.last_name END AS name,
                       r.status, r.check_in_time, r.action, r.unmatched_reason, r.applied
                FROM attendance_import_rows r
                LEFT JOIN student_profiles sp ON sp.student_id = r.student_id
                LEFT JOIN users u ON u.user_id = r.student_id
                WHERE r.import_id = ?
                ORDER BY r.row_no
                """, (rs, i) -> new ImportRow(rs.getInt("row_no"), rs.getString("raw_identifier"),
                        rs.getString("raw_join_time"), rs.getString("raw_status"),
                        rs.getObject("student_id", UUID.class), rs.getString("student_code"), rs.getString("name"),
                        rs.getString("status"), rs.getObject("check_in_time", OffsetDateTime.class),
                        rs.getString("action"), rs.getString("unmatched_reason"), rs.getBoolean("applied")),
                importId);
    }

    /** varchar(255) นับเป็นตัวอักษร (code point) → ตัดตาม code point ไม่ให้ surrogate pair ขาดครึ่ง */
    private static String cut(String s) {
        if (s == null || s.codePointCount(0, s.length()) <= 255) return s;
        return s.substring(0, s.offsetByCodePoints(0, 255));
    }

    private static ImportSession session(ResultSet rs) throws SQLException {
        return new ImportSession(rs.getObject("session_id", UUID.class), rs.getObject("section_id", UUID.class),
                rs.getObject("room_id", UUID.class), rs.getString("course_code"), rs.getString("section_code"),
                rs.getDate("session_date").toLocalDate(), rs.getString("session_status"),
                rs.getTime("start_time").toLocalTime(), rs.getTime("end_time").toLocalTime(),
                rs.getTime("on_time_until").toLocalTime(), rs.getTime("late_until").toLocalTime());
    }
}
