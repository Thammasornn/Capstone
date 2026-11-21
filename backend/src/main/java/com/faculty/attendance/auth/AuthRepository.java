package com.faculty.attendance.auth;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** ตารางที่ใช้: users, students (user_id), lecturers (user_id) */
@Repository
public class AuthRepository {

    public record UserRow(UUID id, String email, String passwordHash, String firstName, String lastName,
                          String role, String status) {}

    public record StudentRow(UUID id, String code, String faculty, String department, Integer yearLevel) {}

    public record LecturerRow(UUID id, String code, String department) {}

    private final JdbcTemplate jdbc;

    public AuthRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<UserRow> findUserByEmail(String email) {
        return one(jdbc.query("""
                SELECT id, email, password_hash, first_name, last_name, role, status
                FROM users WHERE lower(email) = lower(?)
                """, (rs, i) -> user(rs), email));
    }

    public Optional<UserRow> findUserById(UUID id) {
        return one(jdbc.query("""
                SELECT id, email, password_hash, first_name, last_name, role, status
                FROM users WHERE id = ?
                """, (rs, i) -> user(rs), id));
    }

    public Optional<StudentRow> findStudentByUser(UUID userId) {
        return one(jdbc.query("""
                SELECT id, student_code, faculty, department, year_level FROM students WHERE user_id = ?
                """, (rs, i) -> new StudentRow(rs.getObject("id", UUID.class), rs.getString("student_code"),
                rs.getString("faculty"), rs.getString("department"), rs.getObject("year_level", Integer.class)),
                userId));
    }

    public Optional<LecturerRow> findLecturerByUser(UUID userId) {
        return one(jdbc.query("""
                SELECT id, lecturer_code, department FROM lecturers WHERE user_id = ?
                """, (rs, i) -> new LecturerRow(rs.getObject("id", UUID.class), rs.getString("lecturer_code"),
                rs.getString("department")), userId));
    }

    private static UserRow user(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new UserRow(rs.getObject("id", UUID.class), rs.getString("email"), rs.getString("password_hash"),
                rs.getString("first_name"), rs.getString("last_name"), rs.getString("role"),
                rs.getString("status"));
    }

    private static <T> Optional<T> one(List<T> rows) {
        return rows.stream().findFirst();
    }
}
