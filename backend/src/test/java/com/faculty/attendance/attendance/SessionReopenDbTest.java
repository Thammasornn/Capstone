package com.faculty.attendance.attendance;

import static org.assertj.core.api.Assertions.assertThat;

import com.faculty.attendance.attendance.SessionControlRepository.Times;
import java.time.LocalTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * เปิดคาบที่จบแล้วอีกครั้งกับ PostgreSQL จริง (SQL ของ repository) · ทุกอย่างอยู่ใน transaction แล้ว rollback (ไม่ทิ้งข้อมูล)
 * รันเมื่อมี TEST_DB_URL เช่น TEST_DB_URL=jdbc:postgresql://localhost:5432/bangmod_attendance (ข้อมูลจาก ./dev.sh db)
 * ไม่มี = ข้าม (./dev.sh test ไม่ต้องมี DB)
 */
@EnabledIfEnvironmentVariable(named = "TEST_DB_URL", matches = ".+")
class SessionReopenDbTest {

    @Test
    void reopenKeepsCameraRemovesSystemAbsentsAndJobCanMarkAbsentAgain() {
        var ds = new DriverManagerDataSource(System.getenv("TEST_DB_URL"),
                System.getenv().getOrDefault("TEST_DB_USER", "postgres"),
                System.getenv().getOrDefault("TEST_DB_PASSWORD", "postgres"));
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        var tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        var control = new SessionControlRepository(jdbc);
        var attendance = new AttendanceRepository(jdbc);

        tx.executeWithoutResult(status -> {
            status.setRollbackOnly();
            // กลุ่มเรียนที่มีนักศึกษา active อย่างน้อย 2 คน
            UUID section = jdbc.queryForObject("""
                    SELECT e.section_id FROM enrollments e JOIN sections s ON s.section_id = e.section_id
                    WHERE e.status = 'active' AND s.deleted_at IS NULL
                    GROUP BY e.section_id HAVING count(*) >= 2 LIMIT 1
                    """, UUID.class);
            var students = jdbc.queryForList("""
                    SELECT student_id FROM enrollments WHERE section_id = ? AND status = 'active'
                    ORDER BY student_id LIMIT 2
                    """, UUID.class, section);
            UUID camStudent = students.get(0), absentStudent = students.get(1);
            UUID room = jdbc.queryForObject("SELECT room_id FROM sections WHERE section_id = ?", UUID.class, section);
            jdbc.update("DELETE FROM attendance_records WHERE session_id IN (SELECT session_id FROM class_sessions "
                    + "WHERE section_id = ? AND session_date = current_date)", section);
            jdbc.update("DELETE FROM class_sessions WHERE section_id = ? AND session_date = current_date", section);
            jdbc.update("UPDATE enrollments SET enrolled_at = now() - interval '1 day' WHERE section_id = ?", section);
            // คาบวันนี้ที่จบแล้ว 00:00–00:02 · กล้องเห็น 1 คน · อีกคนระบบลงขาด
            UUID sessionId = control.insertInProgress(section, room, java.time.LocalDate.now(com.faculty.attendance.common.Times.BANGKOK),
                    new Times(LocalTime.of(0, 0), LocalTime.of(0, 2), LocalTime.of(0, 0), LocalTime.of(0, 1),
                            LocalTime.of(0, 1), LocalTime.of(0, 1)));
            jdbc.update("UPDATE class_sessions SET session_status = 'completed' WHERE session_id = ?", sessionId);
            jdbc.update("""
                    INSERT INTO attendance_records (session_id, student_id, status, check_in_time, last_seen_at, source, match_score)
                    VALUES (?, ?, 'on_time', (current_date + time '00:00:30') AT TIME ZONE 'Asia/Bangkok',
                            (current_date + time '00:00:30') AT TIME ZONE 'Asia/Bangkok', 'camera', 0.9)
                    """, sessionId, camStudent);
            jdbc.update("INSERT INTO attendance_records (session_id, student_id, status, source) VALUES (?, ?, 'absent', 'system')",
                    sessionId, absentStudent);

            // เปิดใหม่: ลบขาดของระบบ + ย้ายเวลามาตอนนี้
            int removed = control.deleteSystemAbsents(sessionId);
            control.moveToNow(sessionId, room, new Times(LocalTime.of(0, 0), LocalTime.of(23, 59), LocalTime.of(0, 0),
                    LocalTime.of(0, 0), LocalTime.of(0, 0), LocalTime.of(0, 0)));

            assertThat(removed).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT source FROM attendance_records WHERE session_id = ? AND student_id = ?",
                    String.class, sessionId, camStudent)).isEqualTo("camera");                       // ผลกล้องเดิมยังอยู่
            assertThat(jdbc.queryForObject("SELECT count(*) FROM attendance_records WHERE session_id = ? AND student_id = ?",
                    Integer.class, sessionId, absentStudent)).isZero();                                // ขาดของระบบถูกลบ
            assertThat(jdbc.queryForObject("SELECT session_status FROM class_sessions WHERE session_id = ?",
                    String.class, sessionId)).isEqualTo("in_progress");

            // ปิดเช็คชื่อรอบใหม่แล้ว (00:00 ≤ ตอนนี้) → job ลงขาดใหม่ได้ ไม่ถูกข้าม
            attendance.insertSystemAbsents(1);
            assertThat(jdbc.queryForObject("SELECT source FROM attendance_records WHERE session_id = ? AND student_id = ?",
                    String.class, sessionId, absentStudent)).isEqualTo("system");
            assertThat(jdbc.queryForObject("SELECT source FROM attendance_records WHERE session_id = ? AND student_id = ?",
                    String.class, sessionId, camStudent)).isEqualTo("camera");
        });
    }
}
