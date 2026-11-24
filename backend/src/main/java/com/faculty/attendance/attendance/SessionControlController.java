package com.faculty.attendance.attendance;

import com.faculty.attendance.attendance.SessionControlService.ClassSessionInfo;
import com.faculty.attendance.attendance.SessionControlService.StartSessionRequest;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/** เริ่ม / จบคาบจากหน้า Room Live · admin หรืออาจารย์ที่สอนกลุ่มเรียนนั้น · บันทึก audit_logs ทุกครั้ง */
@RestController
public class SessionControlController {

    private final SessionControlService service;

    public SessionControlController(SessionControlService service) {
        this.service = service;
    }

    /** เริ่มคาบของกลุ่มเรียนนี้ในห้องนี้ "ตอนนี้" (เปิดเช็คชื่อทันที) */
    @PostMapping("/api/rooms/{roomId}/sessions")
    @ResponseStatus(HttpStatus.CREATED)
    public ClassSessionInfo start(@PathVariable UUID roomId, @RequestBody(required = false) StartSessionRequest body,
                                  HttpServletRequest request) {
        return service.startNow(roomId, body, request.getRemoteAddr());
    }

    /** จบคาบที่กำลังเรียน "ตอนนี้" (คนที่ยังไม่มีผลเป็น absent) */
    @PostMapping("/api/class-sessions/{sessionId}/end")
    public ClassSessionInfo end(@PathVariable UUID sessionId, HttpServletRequest request) {
        return service.endNow(sessionId, request.getRemoteAddr());
    }
}
