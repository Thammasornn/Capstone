package com.faculty.attendance.internal;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * ข้อมูลที่ตัวอ่านกล้องของ AI Service ต้องใช้ (GET /api/internal/cameras, /rooms/{roomId}/gallery)
 * embedding ส่งออกไปแบบที่เก็บใน DB (เข้ารหัส AES-GCM ด้วย EmbeddingCipher) — AI Service ถอดรหัสเองด้วย FACE_EMBEDDING_KEY
 */
@Repository
public class CameraFeedRepository {

    public record CameraFeed(String cameraCode, UUID roomId, String streamPath) {}

    public record EmbeddingRow(UUID studentId, byte[] embedding) {}

    private final JdbcTemplate jdbc;

    public CameraFeedRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** กล้องที่เปิดใช้ในห้องที่เปิดใช้ */
    public List<CameraFeed> activeCameras() {
        return jdbc.query("""
                SELECT c.camera_code, c.room_id, c.stream_path
                FROM cameras c
                JOIN rooms r ON r.room_id = c.room_id AND r.is_active
                WHERE c.is_active
                ORDER BY c.camera_code
                """, (rs, i) -> new CameraFeed(rs.getString("camera_code"), rs.getObject("room_id", UUID.class),
                        rs.getString("stream_path")));
    }

    /**
     * embedding ของนักศึกษาที่กล้องเช็คชื่อให้ได้ในกลุ่มเรียนนี้ — เงื่อนไขเดียวกับ AttendanceService.recordSighting:
     * ลงทะเบียน (active) · consent ล่าสุดเป็น granted · บัญชียังเปิดอยู่
     */
    public List<EmbeddingRow> embeddingsForSection(UUID sectionId) {
        return jdbc.query("""
                SELECT fe.student_id, fe.embedding
                FROM face_embeddings fe
                JOIN enrollments e ON e.student_id = fe.student_id AND e.section_id = ? AND e.status = 'active'
                JOIN v_current_consent cc ON cc.student_id = fe.student_id AND cc.action = 'granted'
                JOIN users u ON u.user_id = fe.student_id AND u.is_active
                WHERE fe.model_name = 'sface'
                ORDER BY fe.student_id, fe.created_at
                """, (rs, i) -> new EmbeddingRow(rs.getObject("student_id", UUID.class), rs.getBytes("embedding")),
                sectionId);
    }
}
