package com.faculty.attendance.auth;

import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * ตัดสินสิทธิ์ว่าใครดูข้อมูลอะไรได้ (เรียกก่อนทำงานทุก endpoint ที่มี id)
 *  - admin    : ทุกอย่าง
 *  - lecturer : เฉพาะ section ที่ตัวเองสอน (section_lecturers.lecturer_id = users.user_id)
 *  - student  : เฉพาะข้อมูลของตัวเอง
 * section ที่ถูก soft delete (deleted_at) ถือว่าไม่มี
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
     * หน้าของเจ้าหน้าที่ (Room Monitor, จัดการรายวิชา): admin หรือ lecturer เท่านั้น
     * @return lecturerId ที่ใช้กรอง (null = admin เห็นทั้งหมด)
     */
    public UUID requireStaff() {
        CurrentUser u = current.get();
        if (u.isAdmin()) return null;
        if (u.isLecturer()) return u.lecturerId();
        throw forbidden();
    }

    /** แก้ไขกลุ่มเรียน (B5 / B6): admin หรืออาจารย์ที่สอน section นั้น */
    public void requireSectionStaff(UUID sectionId) {
        CurrentUser u = current.get();
        if (u.isAdmin()) return;
        if (u.isLecturer() && exists("""
                SELECT 1 FROM sections s JOIN section_lecturers sl ON sl.section_id = s.section_id
                WHERE s.section_id = ? AND sl.lecturer_id = ? AND s.deleted_at IS NULL""",
                sectionId, u.lecturerId())) {
            return;
        }
        throw forbidden();
    }

    /**
     * ดูข้อมูลระดับรายวิชา (summary / sessions / students) ได้เฉพาะ admin กับอาจารย์ผู้สอน
     * @return lecturerId ที่ใช้กรอง section (null = admin เห็นทุก section)
     */
    public UUID requireCourseStaff(UUID courseId) {
        CurrentUser u = current.get();
        if (u.isAdmin()) return null;
        if (u.isLecturer() && exists("""
                SELECT 1 FROM sections s JOIN section_lecturers sl ON sl.section_id = s.section_id
                WHERE s.course_id = ? AND sl.lecturer_id = ? AND s.deleted_at IS NULL""",
                courseId, u.lecturerId())) {
            return u.lecturerId();
        }
        throw forbidden();
    }

    public void requireSession(UUID sessionId) {
        CurrentUser u = current.get();
        if (u.isAdmin()) return;
        if (u.isLecturer() && exists("""
                SELECT 1 FROM class_sessions cs
                JOIN sections s ON s.section_id = cs.section_id
                JOIN section_lecturers sl ON sl.section_id = cs.section_id
                WHERE cs.session_id = ? AND sl.lecturer_id = ? AND s.deleted_at IS NULL""",
                sessionId, u.lecturerId())) {
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
                SELECT 1 FROM enrollments e
                JOIN sections s ON s.section_id = e.section_id
                JOIN section_lecturers sl ON sl.section_id = s.section_id
                WHERE e.student_id = ? AND s.course_id = ? AND sl.lecturer_id = ? AND s.deleted_at IS NULL""",
                studentId, courseId, u.lecturerId())) {
            return;
        }
        throw forbidden();
    }

    /** ดูสถานะการลงทะเบียนใบหน้า: admin / นักศึกษาเอง / อาจารย์ที่สอนนักศึกษาคนนั้น (ยังไม่ถอนวิชา) */
    public void requireFaceView(UUID studentId) {
        CurrentUser u = current.get();
        if (u.isAdmin()) return;
        if (u.isStudent() && u.studentId().equals(studentId)) return;
        if (u.isLecturer() && exists("""
                SELECT 1 FROM enrollments e
                JOIN sections s ON s.section_id = e.section_id
                JOIN section_lecturers sl ON sl.section_id = e.section_id
                WHERE e.student_id = ? AND sl.lecturer_id = ?
                  AND e.status = 'active' AND s.deleted_at IS NULL""", studentId, u.lecturerId())) {
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
