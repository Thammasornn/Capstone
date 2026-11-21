package com.faculty.attendance.course;

import com.faculty.attendance.auth.AccessService;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * ตัวเลือกสำหรับฟอร์มรายวิชา (B5) — admin / lecturer เท่านั้น
 *  - GET /api/rooms/options : ทุกห้องที่ใช้งานได้ (GET /rooms ของ B1 กรองเฉพาะห้องที่อาจารย์มีกลุ่ม จึงใช้ทำฟอร์มไม่ได้)
 *  - GET /api/lecturers     : อาจารย์ที่ใช้งานได้ (admin เลือกผู้สอนให้กลุ่มเรียน)
 */
@RestController
@RequestMapping("/api")
public class LookupController {

    public record RoomOption(UUID roomId, String roomCode, String building, Integer capacity) {}

    public record LecturerOption(UUID lecturerId, String name, String email) {}

    private final JdbcTemplate jdbc;
    private final AccessService access;

    public LookupController(JdbcTemplate jdbc, AccessService access) {
        this.jdbc = jdbc;
        this.access = access;
    }

    @GetMapping("/rooms/options")
    public List<RoomOption> rooms() {
        access.requireStaff();
        return jdbc.query("""
                SELECT room_id, room_code, building, capacity FROM rooms WHERE is_active ORDER BY room_code
                """, (rs, i) -> new RoomOption(rs.getObject("room_id", UUID.class), rs.getString("room_code"),
                rs.getString("building"), rs.getObject("capacity", Integer.class)));
    }

    @GetMapping("/lecturers")
    public List<LecturerOption> lecturers() {
        access.requireStaff();
        return jdbc.query("""
                SELECT user_id, concat_ws(' ', title, first_name, last_name) AS name, email
                FROM users WHERE role = 'lecturer' AND is_active
                ORDER BY first_name, last_name
                """, (rs, i) -> new LecturerOption(rs.getObject("user_id", UUID.class), rs.getString("name"),
                rs.getString("email")));
    }
}
