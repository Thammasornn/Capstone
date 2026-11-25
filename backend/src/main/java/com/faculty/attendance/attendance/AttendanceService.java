package com.faculty.attendance.attendance;

import com.faculty.attendance.attendance.AttendanceDtos.*;
import com.faculty.attendance.attendance.AttendanceRepository.RecordRow;
import com.faculty.attendance.attendance.AttendanceRepository.SessionCtx;
import com.faculty.attendance.audit.AuditLogRepository;
import com.faculty.attendance.auth.AccessService;
import com.faculty.attendance.common.Times;
import com.faculty.attendance.room.RoomDtos.CheckInEvent;
import com.faculty.attendance.room.RoomEventHub;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

/**
 * เขียน attendance_records 2 ทาง:
 *  - B8 อาจารย์เช็คชื่อด้วยมือ (source = manual + audit_logs)
 *  - กล้อง (AI Service -> /api/internal) ตามกติกา "จัดสถานะตอนกล้องเห็นครั้งแรก" ใน database-design.md
 * ทั้งสองทางส่ง event check_in ไปที่ห้องของคาบ (B3)
 */
@Service
public class AttendanceService {

    private static final Set<String> STATUSES = Set.of("on_time", "late", "absent");

    private final AttendanceRepository repo;
    private final AccessService access;
    private final AuditLogRepository audit;
    private final RoomEventHub hub;
    private final TransactionTemplate tx;
    private final JsonMapper json;

    public AttendanceService(AttendanceRepository repo, AccessService access, AuditLogRepository audit,
                             RoomEventHub hub, TransactionTemplate tx, JsonMapper json) {
        this.repo = repo;
        this.access = access;
        this.audit = audit;
        this.hub = hub;
        this.tx = tx;
        this.json = json;
    }

    // ---------- B8 ----------

    public ManualAttendanceResponse setManual(UUID sessionId, UUID studentId, ManualAttendanceRequest req,
                                              String ip) {
        access.requireSession(sessionId);
        UUID actorId = access.user().userId();
        SessionCtx s = repo.findSession(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "ไม่พบคาบเรียน"));
        if ("cancelled".equals(s.sessionStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "คาบนี้ถูกยกเลิกแล้ว");
        }
        if (!s.started()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "คาบนี้ยังไม่เริ่ม เช็คชื่อไม่ได้");
        }
        String status = req == null || req.status() == null ? null
                : req.status().trim().toLowerCase().replace('-', '_');
        if (!STATUSES.contains(status)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ค่า status ต้องเป็น on_time, late หรือ absent");
        }
        String reason = req.reason() == null ? "" : req.reason().trim();
        if (reason.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "กรุณาระบุเหตุผลการแก้ไขการเช็คชื่อ");
        }
        if (reason.length() > 500) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "เหตุผลยาวได้ไม่เกิน 500 ตัวอักษร");
        }
        var student = repo.enrolledStudent(s.sectionId(), studentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "นักศึกษาไม่ได้อยู่ในกลุ่มเรียนนี้"));
        if (req.checkInTime() != null && !"absent".equals(status)) {
            if (req.checkInTime().isBefore(s.at(s.startTime()).minusHours(1))
                    || req.checkInTime().isAfter(s.at(s.endTime()))) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "เวลาเช็คชื่อต้องอยู่ในช่วงเวลาของคาบ");
            }
        }

        RecordRow after = tx.execute(t -> {
            RecordRow before = repo.findRecordForUpdate(sessionId, studentId).orElse(null);
            LocalDateTime checkIn = "absent".equals(status) ? null
                    : req.checkInTime() != null ? req.checkInTime().withNano(0)
                    : before != null && before.checkInTime() != null ? Times.local(before.checkInTime())
                    : s.at(s.startTime());
            repo.upsertManual(sessionId, studentId, status, Times.offset(checkIn), actorId, reason);
            RecordRow saved = repo.findRecordForUpdate(sessionId, studentId).orElseThrow();
            audit.log(actorId, "attendance.manual_set", "attendance_record", saved.attendanceId(),
                    before == null ? null : toJson(snapshot(before)), toJson(snapshot(saved)), ip);
            return saved;
        });

        hub.publish(s.roomId(), "check_in", new CheckInEvent(sessionId, studentId, student.studentCode(),
                Times.local(after.checkInTime()), after.status(), "manual"));
        return new ManualAttendanceResponse(sessionId, studentId, student.studentCode(), student.studentName(),
                after.status(), Times.local(after.checkInTime()), Times.local(after.lastSeenAt()), after.source(),
                after.reason(), after.recordedBy());
    }

    // ---------- กล้อง ----------

    public SightingResult recordSighting(UUID roomId, Sighting in) {
        if (in == null || in.studentId() == null || in.matchScore() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ต้องมี studentId และ matchScore");
        }
        if (in.matchScore().compareTo(BigDecimal.ONE.negate()) < 0 || in.matchScore().compareTo(BigDecimal.ONE) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "matchScore ต้องอยู่ระหว่าง -1 ถึง 1");
        }
        LocalDateTime now = Times.now().withNano(0);
        LocalDateTime seenAt = in.seenAt() == null ? now : in.seenAt().withNano(0);
        if (seenAt.isAfter(now.plusMinutes(1))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "seenAt อยู่ในอนาคต");
        }

        var found = repo.sessionInRoomAt(roomId, seenAt);
        if (found.isEmpty()) return ignored("no_session", null);
        SessionCtx s = found.get();
        if (!s.cameraEnabled()) return ignored("camera_disabled", s.sessionId());
        var student = repo.enrolledStudent(s.sectionId(), in.studentId());
        if (student.isEmpty()) return ignored("not_enrolled", s.sessionId());
        if (!repo.hasGrantedConsent(in.studentId())) return ignored("no_consent", s.sessionId());

        SightingResult result = tx.execute(t -> {
            var existing = repo.findRecordForUpdate(s.sessionId(), in.studentId());
            if (existing.isPresent()) {
                RecordRow r = existing.get();
                if (!"camera".equals(r.source())) {     // กล้องไม่ทับแถว manual / import / system
                    String why = switch (r.source()) {
                        case "manual" -> "manual_record";
                        case "import" -> "import_record";
                        default -> "check_in_closed";
                    };
                    return ignored(why, s.sessionId());
                }
                repo.touchCameraLastSeen(s.sessionId(), in.studentId(), Times.offset(seenAt));
                return new SightingResult("seen", null, s.sessionId(), r.status());
            }
            String status;
            if (!seenAt.isAfter(s.at(s.onTimeUntil()))) status = "on_time";
            else if (!seenAt.isAfter(s.at(s.lateUntil()))) status = "late";
            else return ignored("check_in_closed", s.sessionId());     // หลัง late_until: ไม่บันทึกสถานะ
            boolean inserted = repo.insertCamera(s.sessionId(), in.studentId(), status, Times.offset(seenAt),
                    in.matchScore());
            return inserted ? new SightingResult("checked_in", null, s.sessionId(), status)
                    : new SightingResult("seen", null, s.sessionId(), null);
        });

        if ("checked_in".equals(result.result())) {
            hub.publish(roomId, "check_in", new CheckInEvent(s.sessionId(), in.studentId(),
                    student.get().studentCode(), seenAt, result.status(), "camera"));
        }
        return result;
    }

    private static SightingResult ignored(String reason, UUID sessionId) {
        return new SightingResult("ignored", reason, sessionId, null);
    }

    /** key แบบ snake_case ตามแถว audit ใน 02_seed_mock.sql */
    private static Map<String, Object> snapshot(RecordRow r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", r.status());
        m.put("check_in_time", r.checkInTime() == null ? null : Times.local(r.checkInTime()).toString());
        m.put("source", r.source());
        m.put("reason", r.reason());
        return m;
    }

    private String toJson(Object o) {
        return json.writeValueAsString(o);
    }
}
