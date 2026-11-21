package com.faculty.attendance.dashboard;

import com.faculty.attendance.dashboard.DashboardDtos.*;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

/** Attendance Dashboard API (อ่านอย่างเดียว) ต้องแนบ Authorization: Bearer <token> */
@RestController
@RequestMapping("/api")
public class DashboardController {

    private final DashboardService service;

    public DashboardController(DashboardService service) {
        this.service = service;
    }

    /** ตัวเลือกรายวิชา/กลุ่มเรียนตามสิทธิ์ผู้ใช้ */
    @GetMapping("/courses")
    public List<CourseItem> courses() {
        return service.courses();
    }

    @GetMapping("/courses/{courseId}/attendance/summary")
    public CourseSummaryResponse courseSummary(@PathVariable UUID courseId,
                                               @RequestParam(required = false) UUID sectionId) {
        return service.courseSummary(courseId, sectionId);
    }

    /** ทุกคาบพร้อมตัวเลขต่อคาบ ใช้ทำกราฟแนวโน้มและ dropdown เลือกคาบ */
    @GetMapping("/courses/{courseId}/attendance/sessions")
    public SessionListResponse sessions(@PathVariable UUID courseId,
                                        @RequestParam(required = false) UUID sectionId) {
        return service.sessions(courseId, sectionId);
    }

    /** ตารางนักศึกษา + สถานะลงทะเบียนใบหน้า (?faceRegistered=false = คนที่ยังไม่ลงทะเบียน) */
    @GetMapping("/courses/{courseId}/attendance/students")
    public CourseStudentsResponse students(@PathVariable UUID courseId,
                                           @RequestParam(required = false) UUID sectionId,
                                           @RequestParam(required = false) Boolean faceRegistered) {
        return service.students(courseId, sectionId, faceRegistered);
    }

    @GetMapping("/class-sessions/{sessionId}/attendance/summary")
    public SessionSummaryResponse sessionSummary(@PathVariable UUID sessionId) {
        return service.sessionSummary(sessionId);
    }

    /** ?status=on_time|late|absent (ไม่ใส่ = ทั้งหมด) */
    @GetMapping("/class-sessions/{sessionId}/attendance")
    public SessionAttendanceResponse sessionAttendance(@PathVariable UUID sessionId,
                                                       @RequestParam(required = false) String status) {
        return service.sessionAttendance(sessionId, status);
    }

    @GetMapping("/courses/{courseId}/students/{studentId}/attendance")
    public StudentAttendanceResponse studentAttendance(@PathVariable UUID courseId,
                                                       @PathVariable UUID studentId) {
        return service.studentAttendance(courseId, studentId);
    }
}
