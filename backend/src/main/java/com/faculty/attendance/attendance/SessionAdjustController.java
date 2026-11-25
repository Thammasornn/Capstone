package com.faculty.attendance.attendance;

import com.faculty.attendance.attendance.SessionAdjustService.AdjustResponse;
import com.faculty.attendance.attendance.SessionAdjustService.CheckInWindowRequest;
import com.faculty.attendance.attendance.SessionAdjustService.ExtendRequest;
import com.faculty.attendance.attendance.SessionAdjustService.ReanchorRequest;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

/**
 * ปรับเวลาคาบที่เริ่มแล้ว (คาบเดียว) · admin หรืออาจารย์ที่สอนกลุ่มเรียนนั้น · reason บังคับ · dryRun = ดูผลก่อนบันทึก
 * รายละเอียดกติกาอยู่ที่ SessionAdjustService
 */
@RestController
@RequestMapping("/api/class-sessions/{sessionId}")
public class SessionAdjustController {

    private final SessionAdjustService service;

    public SessionAdjustController(SessionAdjustService service) {
        this.service = service;
    }

    /** อาจารย์เข้าสาย / เริ่มจริงช้ากว่าตาราง → เลื่อนเวลาเริ่ม + ช่วงเช็คชื่อ */
    @PostMapping("/reanchor")
    public AdjustResponse reanchor(@PathVariable UUID sessionId, @RequestBody(required = false) ReanchorRequest body,
                                   HttpServletRequest request) {
        return service.reanchor(sessionId, body, request.getRemoteAddr());
    }

    /** เลิกช้า / ปรับเวลาจบ */
    @PostMapping("/extend")
    public AdjustResponse extend(@PathVariable UUID sessionId, @RequestBody(required = false) ExtendRequest body,
                                 HttpServletRequest request) {
        return service.extend(sessionId, body, request.getRemoteAddr());
    }

    /** ขยาย / ปรับช่วงตรงเวลา / สาย / ปิดเช็คชื่อ */
    @PostMapping("/check-in-window")
    public AdjustResponse checkInWindow(@PathVariable UUID sessionId,
                                        @RequestBody(required = false) CheckInWindowRequest body,
                                        HttpServletRequest request) {
        return service.checkInWindow(sessionId, body, request.getRemoteAddr());
    }
}
