package com.faculty.attendance.dashboard;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/**
 * ข้อมูลสำหรับ Dashboard ใน Vue
 *  - เวลาทั้งหมดเป็นเวลาไทย ไม่มี timezone (ISO เช่น "2026-11-16T09:05:00", "09:00:00")
 *  - status: "on_time" | "late" | "absent"
 *  - attendanceRate เป็น % (0-100) = (on_time + late) / ทั้งหมด
 */
public final class DashboardDtos {
    private DashboardDtos() {}

    public record StatusCounts(int onTime, int late, int absent) {
        public int total() { return onTime + late + absent; }
        public double attendanceRate() {
            return total() == 0 ? 0.0 : Math.round((onTime + late) * 10000.0 / total()) / 100.0;
        }
    }

    // ---- GET /courses : ตัวเลือกรายวิชา/กลุ่มเรียน (dropdown) ตามสิทธิ์ผู้ใช้ ----
    /** field ตั้งแต่ lecturerId เป็นต้นไป = ตารางเรียนที่ขอเพิ่มใน B5 (classDays = MON–SUN) */
    public record SectionItem(UUID sectionId, String sectionCode, String semester, Integer academicYear,
                              String lecturerName, UUID lecturerId, UUID roomId, String roomCode,
                              List<String> classDays, LocalTime startTime, LocalTime endTime,
                              LocalTime checkInOpens, LocalTime checkInCloses, boolean cameraAttendanceEnabled) {}

    public record CourseItem(UUID courseId, String courseCode, String courseName, List<SectionItem> sections) {}

    // ---- GET /courses/{courseId}/attendance/summary[?sectionId=] : การ์ดตัวเลขรวม ----
    public record CourseSummaryResponse(UUID courseId, String courseCode, String courseName, UUID sectionId,
                                        int totalSessions, int totalStudents,
                                        int onTime, int late, int absent, double attendanceRate) {}

    // ---- GET /courses/{courseId}/attendance/sessions[?sectionId=] : กราฟแนวโน้ม + ตัวเลือกคาบ ----
    /** started=false (คาบยังไม่เริ่ม) -> onTime/late/absent = 0 ให้ FE ข้ามจุดนี้ในกราฟ */
    public record SessionItem(UUID sessionId, UUID sectionId, String sectionCode, LocalDate sessionDate,
                              LocalTime startTime, LocalTime endTime, String sessionStatus, boolean started,
                              int totalStudents, int onTime, int late, int absent, double attendanceRate) {}

    public record SessionListResponse(UUID courseId, List<SessionItem> items) {}

    // ---- GET /courses/{courseId}/attendance/students[?sectionId=&faceRegistered=] : ตารางนักศึกษา ----
    public record StudentSummaryRow(UUID studentId, String studentCode, String studentName,
                                    int onTime, int late, int absent, double attendanceRate,
                                    boolean faceRegistered) {}

    public record CourseStudentsResponse(UUID courseId, List<StudentSummaryRow> items) {}

    // ---- GET /class-sessions/{sessionId}/attendance/summary ----
    public record SessionSummaryResponse(UUID sessionId, UUID sectionId, String sectionCode, UUID courseId,
                                         LocalDate sessionDate, LocalTime startTime, LocalTime endTime,
                                         String sessionStatus, String checkInOpens, String onTimeUntil,
                                         String lateUntil, String checkInCloses, int totalStudents,
                                         int onTime, int late, int absent, double attendanceRate) {}

    // ---- GET /class-sessions/{sessionId}/attendance[?status=] ----
    /**
     * source = camera | manual | system | import | null (null = ยังไม่มีแถว ถือว่า absent) · ขอเพิ่มใน B8
     * importedFrom = method ของการนำเข้า (template | teams | zoom | google_form | paper | other) เมื่อ source = import · อื่น ๆ null
     */
    public record AttendanceRow(UUID studentId, String studentCode, String studentName,
                                LocalDateTime checkInTime, LocalDateTime checkOutTime, String status,
                                String source, String importedFrom) {}

    public record SessionAttendanceResponse(UUID sessionId, LocalDate sessionDate, List<AttendanceRow> items) {}

    // ---- GET /courses/{courseId}/students/{studentId}/attendance ----
    public record StudentSessionRow(UUID sessionId, LocalDate sessionDate,
                                    LocalDateTime checkInTime, LocalDateTime checkOutTime, String status) {}

    public record StudentAttendanceResponse(UUID courseId, String courseCode, String courseName,
                                            UUID studentId, String studentCode, String studentName,
                                            int onTime, int late, int absent, double attendanceRate,
                                            List<StudentSessionRow> items) {}
}
