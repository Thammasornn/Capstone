package com.faculty.attendance.dashboard;

import com.faculty.attendance.auth.AccessService;
import com.faculty.attendance.auth.CurrentUser;
import com.faculty.attendance.dashboard.DashboardDtos.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class DashboardService {

    private static final Set<String> STATUSES = Set.of("on_time", "late", "absent");

    private final DashboardRepository repo;
    private final AccessService access;

    public DashboardService(DashboardRepository repo, AccessService access) {
        this.repo = repo;
        this.access = access;
    }

    /** รายวิชา/กลุ่มเรียนที่ผู้ใช้เห็นได้: admin ทั้งหมด, อาจารย์ที่สอน, นักศึกษาที่ลงทะเบียน */
    public List<CourseItem> courses() {
        CurrentUser u = access.user();
        UUID lecturerId = null;
        UUID studentId = null;
        if (u.isLecturer()) lecturerId = u.lecturerId();
        else if (u.isStudent()) studentId = u.studentId();
        else if (!u.isAdmin()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงข้อมูลนี้");

        Map<UUID, CourseItem> byCourse = new LinkedHashMap<>();
        for (var r : repo.courseSections(lecturerId, studentId)) {
            byCourse.computeIfAbsent(r.courseId(),
                    k -> new CourseItem(r.courseId(), r.courseCode(), r.courseName(), new ArrayList<>()))
                    .sections().add(new SectionItem(r.sectionId(), r.sectionCode(), r.semester(),
                            r.academicYear(), r.lecturerName(), r.lecturerId(), r.roomId(), r.roomCode(),
                            r.classDays(), r.startTime(), r.endTime(), r.checkInOpens(), r.checkInCloses(),
                            r.cameraAttendanceEnabled()));
        }
        return new ArrayList<>(byCourse.values());
    }

    public CourseSummaryResponse courseSummary(UUID courseId, UUID sectionId) {
        UUID scope = access.requireCourseStaff(courseId);
        var course = requireCourse(courseId);
        checkSection(courseId, sectionId);
        var agg = repo.courseSummary(courseId, sectionId, scope);
        var c = agg.counts();
        return new CourseSummaryResponse(courseId, course.code(), course.name(), sectionId, agg.sessions(),
                agg.students(), c.onTime(), c.late(), c.absent(), c.attendanceRate());
    }

    public SessionListResponse sessions(UUID courseId, UUID sectionId) {
        UUID scope = access.requireCourseStaff(courseId);
        requireCourse(courseId);
        checkSection(courseId, sectionId);
        return new SessionListResponse(courseId, repo.sessionList(courseId, sectionId, scope));
    }

    public CourseStudentsResponse students(UUID courseId, UUID sectionId, Boolean faceRegistered) {
        UUID scope = access.requireCourseStaff(courseId);
        requireCourse(courseId);
        checkSection(courseId, sectionId);
        var rows = repo.studentSummaries(courseId, sectionId, scope);
        if (faceRegistered != null) {
            rows = rows.stream().filter(r -> r.faceRegistered() == faceRegistered).toList();
        }
        return new CourseStudentsResponse(courseId, rows);
    }

    public SessionSummaryResponse sessionSummary(UUID sessionId) {
        access.requireSession(sessionId);
        var s = requireSession(sessionId);
        var c = repo.sessionSummary(sessionId);
        return new SessionSummaryResponse(sessionId, s.sectionId(), s.sectionCode(), s.courseId(),
                s.sessionDate(), s.startTime(), s.endTime(), s.status(), s.checkInOpens(), s.onTimeUntil(),
                s.lateUntil(), s.checkInCloses(), c.total(), c.onTime(), c.late(), c.absent(), c.attendanceRate());
    }

    public SessionAttendanceResponse sessionAttendance(UUID sessionId, String status) {
        access.requireSession(sessionId);
        var session = requireSession(sessionId);
        String normalized = null;
        if (status != null && !status.isBlank()) {
            normalized = status.trim().toLowerCase().replace('-', '_');
            if (!STATUSES.contains(normalized)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "status ต้องเป็น on_time, late หรือ absent");
            }
        }
        return new SessionAttendanceResponse(sessionId, session.sessionDate(),
                repo.sessionAttendance(sessionId, normalized));
    }

    public StudentAttendanceResponse studentAttendance(UUID courseId, UUID studentId) {
        access.requireStudentInCourse(courseId, studentId);
        var course = requireCourse(courseId);
        var student = repo.findEnrolledStudent(courseId, studentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "ไม่พบนักศึกษาในรายวิชานี้"));
        List<StudentSessionRow> items = repo.studentAttendance(courseId, studentId);
        int onTime = 0, late = 0, absent = 0;
        for (StudentSessionRow r : items) {
            switch (r.status()) {
                case "on_time" -> onTime++;
                case "late" -> late++;
                default -> absent++;
            }
        }
        var counts = new StatusCounts(onTime, late, absent);
        return new StudentAttendanceResponse(courseId, course.code(), course.name(), studentId,
                student.code(), student.name(), onTime, late, absent, counts.attendanceRate(), items);
    }

    private DashboardRepository.CourseInfo requireCourse(UUID courseId) {
        return repo.findCourse(courseId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "ไม่พบรายวิชา"));
    }

    private void checkSection(UUID courseId, UUID sectionId) {
        if (sectionId != null && !repo.sectionInCourse(sectionId, courseId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "ไม่พบกลุ่มเรียนในรายวิชานี้");
        }
    }

    private DashboardRepository.SessionInfo requireSession(UUID sessionId) {
        return repo.findSession(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "ไม่พบคาบเรียน"));
    }
}
