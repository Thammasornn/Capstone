package com.faculty.attendance.auth;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * ตารางที่ใช้ (backend/db/01_schema.sql): users, student_profiles, semesters
 *  - student_profiles.student_id = users.user_id
 *  - อาจารย์ไม่มีตารางแยก: users ที่ role = 'lecturer' (lecturerId = user_id)
 */
@Repository
public class AuthRepository {

    public record UserRow(UUID id, String email, String passwordHash, String title, String firstName,
                          String lastName, String role, boolean active) {}

    /** yearLevel คำนวณจาก entry_year เทียบปีการศึกษาของภาคเรียนล่าสุดที่เริ่มแล้ว (O1: ไม่เก็บ year_level) */
    public record StudentRow(UUID id, String code, String faculty, String program, Integer entryYear,
                             Integer yearLevel, String academicStatus) {}

    private static final String USER_COLUMNS =
            "user_id, email, password_hash, title, first_name, last_name, role, is_active";

    private final JdbcTemplate jdbc;

    public AuthRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** users.email เก็บเป็นตัวพิมพ์เล็กเสมอ (CHECK email = lower(email)) */
    public Optional<UserRow> findUserByEmail(String email) {
        return one(jdbc.query("SELECT " + USER_COLUMNS + " FROM users WHERE email = lower(?)",
                (rs, i) -> user(rs), email));
    }

    public Optional<UserRow> findUserById(UUID id) {
        return one(jdbc.query("SELECT " + USER_COLUMNS + " FROM users WHERE user_id = ?",
                (rs, i) -> user(rs), id));
    }

    public void touchLastLogin(UUID userId) {
        jdbc.update("UPDATE users SET last_login_at = now() WHERE user_id = ?", userId);
    }

    public Optional<StudentRow> findStudentByUser(UUID userId) {
        return one(jdbc.query("""
                SELECT sp.student_id, sp.student_code, sp.faculty, sp.program, sp.entry_year,
                       sp.academic_status,
                       (SELECT max(se.academic_year) FROM semesters se WHERE se.start_date <= current_date)
                         - sp.entry_year + 1 AS year_level
                FROM student_profiles sp
                WHERE sp.student_id = ?
                """, (rs, i) -> new StudentRow(rs.getObject("student_id", UUID.class),
                rs.getString("student_code"), rs.getString("faculty"), rs.getString("program"),
                rs.getObject("entry_year", Integer.class), rs.getObject("year_level", Integer.class),
                rs.getString("academic_status")), userId));
    }

    private static UserRow user(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new UserRow(rs.getObject("user_id", UUID.class), rs.getString("email"),
                rs.getString("password_hash"), rs.getString("title"), rs.getString("first_name"),
                rs.getString("last_name"), rs.getString("role"), rs.getBoolean("is_active"));
    }

    private static <T> Optional<T> one(List<T> rows) {
        return rows.stream().findFirst();
    }
}
