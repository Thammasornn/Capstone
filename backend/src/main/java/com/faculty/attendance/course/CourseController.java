package com.faculty.attendance.course;

import com.faculty.attendance.course.CourseDtos.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/** Manage / Edit Course (B5) + สวิตช์กล้อง (B6) · GET /courses (รายการ) อยู่ที่ DashboardController */
@RestController
@RequestMapping("/api")
public class CourseController {

    private final CourseService service;

    public CourseController(CourseService service) {
        this.service = service;
    }

    @GetMapping("/courses/{courseId}")
    public CourseDetail get(@PathVariable UUID courseId) {
        return service.get(courseId);
    }

    @PostMapping("/courses")
    @ResponseStatus(HttpStatus.CREATED)
    public CourseDetail create(@RequestBody(required = false) CourseInput body, HttpServletRequest request) {
        return service.create(body, request.getRemoteAddr());
    }

    @PutMapping("/courses/{courseId}")
    public CourseDetail update(@PathVariable UUID courseId, @RequestBody(required = false) CourseInput body,
                               HttpServletRequest request) {
        return service.update(courseId, body, request.getRemoteAddr());
    }

    /** soft delete (Q5) · ประวัติเช็คชื่อยังอยู่ · คาบที่ยังไม่เริ่มถูกยกเลิก */
    @DeleteMapping("/courses/{courseId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID courseId, HttpServletRequest request) {
        service.delete(courseId, request.getRemoteAddr());
    }

    /** B6 */
    @PatchMapping("/sections/{sectionId}/camera-attendance")
    public CameraToggleResponse cameraAttendance(@PathVariable UUID sectionId,
                                                 @RequestBody(required = false) CameraToggleRequest body,
                                                 HttpServletRequest request) {
        return service.setCameraAttendance(sectionId, body, request.getRemoteAddr());
    }
}
