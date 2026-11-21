package com.faculty.attendance.auth;

import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * ตัดสินสิทธิ์ว่าใครดูข้อมูลอะไรได้ (เรียกก่อนทำงานทุก endpoint ที่มี id)
 *  - admin    : ทุกอย่าง
 *  - lecturer : เฉพาะ section ที่ตัวเองสอน (course_sections.lecturer_id)
 *  - student  : เฉพาะข้อมูลของตัวเอง
 */
@Service
public class AccessService {

    private final CurrentUserProvider current;
    private final JdbcTemplate jdbc;

    public AccessService(CurrentUserProvider current, JdbcTemplate jdbc) {
        this.current = current;
        this.jdbc = jdbc;
    }

    public CurrentUser user() {
        return current.get();
    }

    /**
     * ดูข้อมูลระดับรายวิชา (summary / sessions / students) ได้เฉพาะ admin กับอาจารย์ผู้สอน
     * @return lecturerId ที่ใช้กรอง section (null = admin เห็นทุก section)
     */
    public UUID requireCourseStaff(UUID courseId) {
        CurrentUser u = current.get();
        if (u.isAdmin()) return null;
        if (u.isLecturer() && exists("SELECT 1 FROM course_sections WHERE course_id = ? AND lecturer_id = ?",
                courseId, u.lecturerId())) {
            return u.lecturerId();
        }
        throw forbidden();
    }

    public void requireSession(UUID sessionId) {
        CurrentUser u = current.get();
        if (u.isAdmin()) return;
        if (u.isLecturer() && exists("""
                SELECT 1 FROM class_sessions cs JOIN course_sections sec ON sec.id = cs.section_id
                WHERE cs.id = ? AND sec.lecturer_id = ?""", sessionId, u.lecturerId())) {
            return;
        }
        throw forbidden();
    }

    /** ประวัติรายคนในรายวิชา: admin / อาจารย์ที่สอนนักศึกษาคนนั้นในวิชานี้ / นักศึกษาเอง */
    public void requireStudentInCourse(UUID courseId, UUID studentId) {
        CurrentUser u = current.get();
        if (u.isAdmin()) return;
        if (u.isStudent() && u.studentId().equals(studentId)) return;
        if (u.isLecturer() && exists("""
                SELECT 1 FROM course_enrollments ce JOIN course_sections sec ON sec.id = ce.section_id
                WHERE ce.student_id = ? AND sec.course_id = ? AND sec.lecturer_id = ?""",
                studentId, courseId, u.lecturerId())) {
            return;
        }
        throw forbidden();
    }

    /** ดูสถานะการลงทะเบียนใบหน้า: admin / นักศึกษาเอง / อาจารย์ที่สอนนักศึกษาคนนั้น */
    public void requireFaceView(UUID studentId) {
        CurrentUser u = current.get();
        if (u.isAdmin()) return;
        if (u.isStudent() && u.studentId().equals(studentId)) return;
        if (u.isLecturer() && exists("""
                SELECT 1 FROM course_enrollments ce JOIN course_sections sec ON sec.id = ce.section_id
                WHERE ce.student_id = ? AND sec.lecturer_id = ?""", studentId, u.lecturerId())) {
            return;
        }
        throw forbidden();
    }

    /** ลงทะเบียนใบหน้า: เฉพาะนักศึกษาเจ้าของบัญชี หรือ admin */
    public void requireFaceRegister(UUID studentId) {
        CurrentUser u = current.get();
        if (u.isAdmin() || (u.isStudent() && u.studentId().equals(studentId))) return;
        throw forbidden();
    }

    private boolean exists(String sql, Object... args) {
        return !jdbc.queryForList(sql + " LIMIT 1", args).isEmpty();
    }

    private static ResponseStatusException forbidden() {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงข้อมูลนี้");
    }
}
