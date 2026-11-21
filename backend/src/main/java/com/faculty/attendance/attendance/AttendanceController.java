package com.faculty.attendance.attendance;

import com.faculty.attendance.attendance.AttendanceDtos.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/class-sessions/{sessionId}/attendance")
public class AttendanceController {

    private final AttendanceService service;

    public AttendanceController(AttendanceService service) {
        this.service = service;
    }

    /** B8 อาจารย์เช็คชื่อด้วยมือ: admin / อาจารย์ที่สอนคาบนั้น · บันทึก audit_logs ทุกครั้ง */
    @PutMapping("/{studentId}")
    public ManualAttendanceResponse setManual(@PathVariable UUID sessionId, @PathVariable UUID studentId,
                                              @RequestBody(required = false) ManualAttendanceRequest body,
                                              HttpServletRequest request) {
        return service.setManual(sessionId, studentId, body, request.getRemoteAddr());
    }
}
