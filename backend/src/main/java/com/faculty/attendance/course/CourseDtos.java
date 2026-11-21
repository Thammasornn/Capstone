package com.faculty.attendance.course;

import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/**
 * B5–B6 · shape ตาม frontend/src/features/course-management/api.js
 * เวลาเป็น "HH:mm:ss" เวลาไทย · classDays = MON–SUN
 */
public final class CourseDtos {
    private CourseDtos() {}

    /**
     * sectionId ไม่มี = สร้างกลุ่มใหม่ · lecturerId: admin ต้องระบุ, lecturer ไม่ระบุ = ตัวเอง
     * onTimeMinutes ไม่ใส่ = 15 นาที (หรือเท่าช่วงเช็คชื่อถ้าสั้นกว่า) · lateUntil ไม่ใส่ = สายได้จนปิดเช็คชื่อ (R2)
     */
    public record SectionInput(UUID sectionId, String sectionCode, UUID roomId, UUID lecturerId,
                               List<String> classDays, LocalTime startTime, LocalTime endTime,
                               LocalTime checkInOpens, LocalTime checkInCloses, Boolean cameraAttendanceEnabled,
                               Integer onTimeMinutes, LocalTime lateUntil) {}

    /** PUT: section ที่ไม่ได้ส่งมาจะคงไว้เหมือนเดิม (ไม่ลบ) */
    public record CourseInput(String courseCode, String courseName, List<SectionInput> sections) {}

    public record SectionDetail(UUID sectionId, String sectionCode, String semester, Integer academicYear,
                                UUID lecturerId, String lecturerName, UUID roomId, String roomCode,
                                List<String> classDays, LocalTime startTime, LocalTime endTime,
                                LocalTime checkInOpens, LocalTime checkInCloses, int onTimeMinutes,
                                LocalTime lateUntil, boolean cameraAttendanceEnabled) {}

    public record CourseDetail(UUID courseId, String courseCode, String courseName, List<SectionDetail> sections) {}

    /** B6 */
    public record CameraToggleRequest(Boolean enabled) {}

    public record CameraToggleResponse(UUID sectionId, boolean cameraAttendanceEnabled) {}
}
