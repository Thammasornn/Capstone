package com.faculty.attendance.attendance;

import com.faculty.attendance.attendance.ClassSessionService.CancelBody;
import com.faculty.attendance.attendance.ClassSessionService.CancelResult;
import com.faculty.attendance.attendance.ClassSessionService.SectionSessions;
import com.faculty.attendance.attendance.ClassSessionService.SessionBody;
import com.faculty.attendance.attendance.ClassSessionService.SessionView;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/** "คาบเรียน" (คาบจริงรายคาบ) · admin หรืออาจารย์ที่สอนกลุ่มนั้น · กติกาอยู่ที่ ClassSessionService */
@RestController
@RequestMapping("/api")
public class ClassSessionController {

    private final ClassSessionService service;

    public ClassSessionController(ClassSessionService service) {
        this.service = service;
    }

    /** ทุกคาบของกลุ่มเรียน + allowedActions ต่อคาบ */
    @GetMapping("/sections/{sectionId}/sessions")
    public SectionSessions list(@PathVariable UUID sectionId) {
        return service.list(sectionId);
    }

    /** เพิ่มคาบชดเชย (makeupForSessionId) / คาบพิเศษ */
    @PostMapping("/sections/{sectionId}/sessions")
    @ResponseStatus(HttpStatus.CREATED)
    public SessionView create(@PathVariable UUID sectionId, @RequestBody(required = false) SessionBody body,
                              HttpServletRequest request) {
        return service.createExtra(sectionId, body, request.getRemoteAddr());
    }

    /** แก้ / เลื่อนคาบที่ยังไม่เริ่ม (ส่งเฉพาะที่เปลี่ยน) */
    @PatchMapping("/class-sessions/{sessionId}")
    public SessionView reschedule(@PathVariable UUID sessionId, @RequestBody(required = false) SessionBody body,
                                  HttpServletRequest request) {
        return service.reschedule(sessionId, body, request.getRemoteAddr());
    }

    /** ยกเลิก (+ makeup = สร้างคาบชดเชยใน transaction เดียวกัน) */
    @PostMapping("/class-sessions/{sessionId}/cancel")
    public CancelResult cancel(@PathVariable UUID sessionId, @RequestBody(required = false) CancelBody body,
                               HttpServletRequest request) {
        return service.cancel(sessionId, body, request.getRemoteAddr());
    }

    /** คืนคาบที่ยกเลิก (คาบในอนาคต) */
    @PostMapping("/class-sessions/{sessionId}/restore")
    public SessionView restore(@PathVariable UUID sessionId, @RequestBody(required = false) CancelBody body,
                               HttpServletRequest request) {
        return service.restore(sessionId, body, request.getRemoteAddr());
    }
}
