package com.faculty.attendance.audit;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * audit_logs: ใครแก้อะไร (เช็คชื่อด้วยมือ, เปิด-ปิดกล้อง, ลบใบหน้า, ลบวิชา)
 * actorId = null หมายถึงระบบ · ห้ามใส่ embedding ใน before / after
 */
@Repository
public class AuditLogRepository {

    private final JdbcTemplate jdbc;

    public AuditLogRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** beforeJson / afterJson = JSON string หรือ null */
    public void log(UUID actorId, String action, String entityType, UUID entityId,
                    String beforeJson, String afterJson, String ip) {
        jdbc.update("""
                INSERT INTO audit_logs (actor_id, action, entity_type, entity_id, before_data, after_data, ip_address)
                VALUES (?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::inet)
                """, actorId, action, entityType, entityId, beforeJson, afterJson, ip);
    }
}
