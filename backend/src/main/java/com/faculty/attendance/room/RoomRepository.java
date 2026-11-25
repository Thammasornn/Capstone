package com.faculty.attendance.room;

import static com.faculty.attendance.common.Times.NOW_TH;

import com.faculty.attendance.room.RoomDtos.CurrentSession;
import com.faculty.attendance.room.RoomDtos.Room;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * ตาราง: rooms, cameras, class_sessions, sections, courses, enrollments, attendance_records, section_lecturers
 * คาบปัจจุบัน = คาบวันนี้ในห้องนี้ที่เวลาไทยตอนนี้อยู่ระหว่าง start_time–end_time และไม่ถูกยกเลิก
 * (ตรรกะเดียวกับ v_room_status แต่เทียบเวลาไทยตรงๆ ไม่ขึ้นกับ timezone ของ DB session · และไม่นับคาบที่ completed แล้ว = จบคาบจากหน้าเว็บมีผลทันที)
 */
@Repository
public class RoomRepository {

    private static final String ROOM_SQL = """
            SELECT r.room_id, r.room_code, c.camera_code, c.status AS camera_status,
                   cur.session_id, cur.course_code, cur.course_name, cur.section_code,
                   cur.start_time, cur.end_time, cur.check_in_opens, cur.on_time_until, cur.late_until, cur.check_in_closes,
                   cur.checked_in, cur.total_students
            FROM rooms r
            LEFT JOIN cameras c ON c.room_id = r.room_id AND c.is_active
            LEFT JOIN LATERAL (
                SELECT cs.session_id, co.course_code, co.course_name, s.section_code, cs.start_time, cs.end_time,
                       to_char(cs.check_in_opens, 'HH24:MI') AS check_in_opens,
                       to_char(cs.on_time_until, 'HH24:MI') AS on_time_until,
                       to_char(cs.late_until, 'HH24:MI') AS late_until,
                       to_char(cs.check_in_closes, 'HH24:MI') AS check_in_closes,
                       (SELECT count(*) FROM attendance_records ar
                         WHERE ar.session_id = cs.session_id AND ar.status IN ('on_time', 'late')) AS checked_in,
                       (SELECT count(*) FROM enrollments e
                         WHERE e.section_id = cs.section_id AND e.status = 'active')              AS total_students
                FROM class_sessions cs
                JOIN sections s  ON s.section_id = cs.section_id AND s.deleted_at IS NULL
                JOIN courses  co ON co.course_id = s.course_id AND co.deleted_at IS NULL
                WHERE cs.room_id = r.room_id
                  AND cs.session_status NOT IN ('cancelled', 'completed')   -- จบคาบจากหน้าเว็บแล้ว = ไม่ใช่คาบปัจจุบันทันที
                  AND cs.session_date = %1$s::date
                  AND %1$s::time BETWEEN cs.start_time AND cs.end_time
                ORDER BY cs.start_time
                LIMIT 1) cur ON true
            WHERE r.is_active
              AND (?::uuid IS NULL OR ?::uuid = r.room_id)
              AND (?::uuid IS NULL OR EXISTS (
                    SELECT 1 FROM sections s JOIN section_lecturers sl ON sl.section_id = s.section_id
                    WHERE sl.lecturer_id = ?::uuid AND s.deleted_at IS NULL
                      AND (s.room_id = r.room_id OR EXISTS (
                            SELECT 1 FROM class_sessions cs2
                            WHERE cs2.section_id = s.section_id AND cs2.room_id = r.room_id))))
            ORDER BY r.room_code
            """.formatted(NOW_TH);

    private final JdbcTemplate jdbc;

    public RoomRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** lecturerId = null -> ทุกห้อง (admin) · ไม่ null -> เฉพาะห้องที่มีกลุ่มเรียน/คาบของอาจารย์คนนั้น */
    public List<Room> list(UUID lecturerId) {
        return jdbc.query(ROOM_SQL, (rs, i) -> room(rs), null, null, lecturerId, lecturerId);
    }

    public Optional<Room> find(UUID roomId, UUID lecturerId) {
        return jdbc.query(ROOM_SQL, (rs, i) -> room(rs), roomId, roomId, lecturerId, lecturerId)
                .stream().findFirst();
    }

    public record CameraRow(UUID cameraId, UUID roomId, String cameraCode, String streamPath, String status) {}

    public Optional<CameraRow> cameraOfRoom(UUID roomId) {
        return jdbc.query("""
                SELECT camera_id, room_id, camera_code, stream_path, status FROM cameras
                WHERE room_id = ? AND is_active
                """, (rs, i) -> camera(rs), roomId).stream().findFirst();
    }

    public Optional<CameraRow> cameraByCode(String cameraCode) {
        return jdbc.query("""
                SELECT camera_id, room_id, camera_code, stream_path, status FROM cameras
                WHERE camera_code = ? AND is_active
                """, (rs, i) -> camera(rs), cameraCode).stream().findFirst();
    }

    public boolean roomExists(UUID roomId) {
        return !jdbc.queryForList("SELECT 1 FROM rooms WHERE room_id = ? AND is_active", roomId).isEmpty();
    }

    /** heartbeat จาก AI Service */
    public void updateCameraStatus(UUID cameraId, String status) {
        jdbc.update("UPDATE cameras SET status = ?, last_seen_at = now() WHERE camera_id = ?", status, cameraId);
    }

    private static CameraRow camera(ResultSet rs) throws SQLException {
        return new CameraRow(rs.getObject("camera_id", UUID.class), rs.getObject("room_id", UUID.class),
                rs.getString("camera_code"), rs.getString("stream_path"), rs.getString("status"));
    }

    private static Room room(ResultSet rs) throws SQLException {
        UUID sessionId = rs.getObject("session_id", UUID.class);
        CurrentSession current = sessionId == null ? null : new CurrentSession(sessionId,
                rs.getString("course_code"), rs.getString("course_name"), rs.getString("section_code"),
                rs.getTime("start_time").toLocalTime(), rs.getTime("end_time").toLocalTime(),
                rs.getString("check_in_opens"), rs.getString("on_time_until"), rs.getString("late_until"),
                rs.getString("check_in_closes"), rs.getInt("checked_in"), rs.getInt("total_students"));
        return new Room(rs.getObject("room_id", UUID.class), rs.getString("room_code"),
                rs.getString("camera_code"), rs.getString("camera_status"), current);
    }
}
