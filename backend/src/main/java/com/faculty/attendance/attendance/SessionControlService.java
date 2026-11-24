package com.faculty.attendance.attendance;

import com.faculty.attendance.attendance.SessionControlRepository.SectionTimes;
import com.faculty.attendance.attendance.SessionControlRepository.SessionRow;
import com.faculty.attendance.attendance.SessionControlRepository.Times;
import com.faculty.attendance.audit.AuditLogRepository;
import com.faculty.attendance.auth.AccessService;
import com.faculty.attendance.room.RoomDtos.SessionEvent;
import com.faculty.attendance.room.RoomEventHub;
import com.faculty.attendance.room.RoomRepository;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * เริ่มคาบ "ตอนนี้" / จบคาบ จากหน้า Room Live (admin หรืออาจารย์ที่สอนกลุ่มเรียนนั้น)
 *
 * เริ่มคาบ: ความยาวคาบ / ช่วงตรงเวลา / ช่วงสาย / ปิดเช็คชื่อ ใช้ค่าของกลุ่มเรียน แต่นับจากเวลานี้ (เปิดเช็คชื่อทันที)
 *   - วันนี้กลุ่มนี้ยังไม่มีคาบ → สร้างคาบใหม่ (in_progress)
 *   - มีคาบที่ยังไม่ถึงเวลา / ถูกยกเลิก → ย้ายคาบนั้นมาเริ่มตอนนี้ในห้องนี้
 *   - มีคาบที่กำลังเรียน หรือจบไปแล้ว → 409 (1 กลุ่มมีได้ 1 คาบต่อวัน)
 *   - ห้องนี้มีคาบอื่นเวลาทับกัน → 409
 * จบคาบ: end_time = ตอนนี้ · ช่วงเช็คชื่อที่ยังไม่ปิดถูกตัดให้จบตอนนี้ → คนที่ยังไม่มีผลเป็น absent (system) ทันที
 * ทุกครั้ง: audit_logs + event session ไปที่ห้อง (หน้า Room Live โหลดใหม่เอง)
 */
@Service
public class SessionControlService {

    public record StartSessionRequest(UUID sectionId) {}

    public record ClassSessionInfo(UUID sessionId, UUID sectionId, UUID roomId, LocalDate sessionDate,
                                   LocalTime startTime, LocalTime endTime, LocalTime onTimeUntil, LocalTime lateUntil,
                                   LocalTime checkInCloses, String sessionStatus) {}

    private static final int LAST_MINUTE = 23 * 60 + 59;

    private final SessionControlRepository repo;
    private final AttendanceRepository attendance;
    private final RoomRepository rooms;
    private final AccessService access;
    private final AuditLogRepository audit;
    private final RoomEventHub hub;
    private final TransactionTemplate tx;

    public SessionControlService(SessionControlRepository repo, AttendanceRepository attendance, RoomRepository rooms,
                                 AccessService access, AuditLogRepository audit, RoomEventHub hub,
                                 TransactionTemplate tx) {
        this.repo = repo;
        this.attendance = attendance;
        this.rooms = rooms;
        this.access = access;
        this.audit = audit;
        this.hub = hub;
        this.tx = tx;
    }

    public ClassSessionInfo startNow(UUID roomId, StartSessionRequest body, String ip) {
        if (body == null || body.sectionId() == null) {
            throw bad("กรุณาเลือกกลุ่มเรียน");
        }
        UUID sectionId = body.sectionId();
        access.requireSectionStaff(sectionId);
        if (!rooms.roomExists(roomId)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "ไม่พบห้องเรียน");
        SectionTimes section = repo.section(sectionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "ไม่พบกลุ่มเรียน"));

        LocalDateTime now = com.faculty.attendance.common.Times.now();
        LocalDate today = now.toLocalDate();
        Times t = plan(section, now.toLocalTime());

        record Result(ClassSessionInfo info, UUID previousRoom) {}
        Result r = tx.execute(s -> {
            UUID previousRoom = null;
            UUID sessionId;
            var existing = repo.sessionOn(sectionId, today);
            repo.roomConflict(roomId, today, t.start(), t.end(), sectionId).ifPresent(other -> {
                throw conflict("ห้องนี้มีคาบ " + other + " ในช่วงเวลานี้แล้ว");
            });
            if (existing.isEmpty()) {
                sessionId = repo.insertInProgress(sectionId, roomId, today, t);
            } else {
                SessionRow e = existing.get();
                boolean started = !e.startTime().isAfter(now.toLocalTime());
                switch (e.sessionStatus()) {
                    case "completed" -> throw conflict("วันนี้กลุ่มเรียนนี้มีคาบไปแล้ว (1 กลุ่มเรียนมีได้ 1 คาบต่อวัน)");
                    case "in_progress" -> throw conflict("กลุ่มเรียนนี้มีคาบที่กำลังเรียนอยู่แล้ว");
                    case "scheduled" -> {
                        if (started) throw conflict("กลุ่มเรียนนี้มีคาบที่กำลังเรียนอยู่แล้ว");
                    }
                    default -> { }   // cancelled → ใช้แถวเดิม เปิดเรียนชดเชยตอนนี้
                }
                sessionId = e.sessionId();
                if (!e.roomId().equals(roomId)) previousRoom = e.roomId();
                repo.moveToNow(sessionId, roomId, t);
            }
            ClassSessionInfo info = info(sessionId, sectionId, roomId, today, t, "in_progress");
            audit.log(access.user().userId(), "session.start_now", "class_session", sessionId, null,
                    "{\"room_id\": \"%s\", \"start_time\": \"%s\", \"end_time\": \"%s\"}"
                            .formatted(roomId, t.start(), t.end()), ip);
            return new Result(info, previousRoom);
        });

        hub.publish(roomId, "session", new SessionEvent(r.info().sessionId(), "in_progress"));
        if (r.previousRoom() != null) {
            hub.publish(r.previousRoom(), "session", new SessionEvent(r.info().sessionId(), "in_progress"));
        }
        return r.info();
    }

    public ClassSessionInfo endNow(UUID sessionId, String ip) {
        LocalDateTime now = com.faculty.attendance.common.Times.now();
        ClassSessionInfo info = tx.execute(s -> {
            SessionRow e = repo.lockSession(sessionId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "ไม่พบคาบเรียน"));
            access.requireSectionStaff(e.sectionId());
            boolean running = e.sessionDate().equals(now.toLocalDate())
                    && !e.startTime().isAfter(now.toLocalTime())
                    && ("in_progress".equals(e.sessionStatus()) || "scheduled".equals(e.sessionStatus()));
            if (!running) throw conflict("คาบนี้ไม่ได้กำลังเรียนอยู่");

            Times t = endPlan(e, now.toLocalTime());
            repo.endAt(sessionId, t);
            audit.log(access.user().userId(), "session.end_now", "class_session", sessionId,
                    "{\"end_time\": \"%s\"}".formatted(e.endTime()), "{\"end_time\": \"%s\"}".formatted(t.end()), ip);
            return info(sessionId, e.sectionId(), e.roomId(), e.sessionDate(), t, "completed");
        });
        attendance.insertSystemAbsents(1);   // คนที่ยังไม่มีผล → absent ทันที (ไม่ต้องรอ job รอบถัดไป)
        hub.publish(info.roomId(), "session", new SessionEvent(sessionId, "completed"));
        return info;
    }

    /** ช่วงเวลาของคาบที่เริ่มตอนนี้: ความยาว / ช่วงเช็คชื่อตามกลุ่มเรียน · ไม่ข้ามเที่ยงคืน (จบอย่างช้า 23:59) */
    static Times plan(SectionTimes s, LocalTime nowTime) {
        int start = nowTime.truncatedTo(ChronoUnit.MINUTES).toSecondOfDay() / 60;
        if (start >= LAST_MINUTE - 1) throw bad("ใกล้เที่ยงคืนเกินไป เริ่มคาบใหม่ไม่ได้");
        int length = minutes(s.startTime(), s.endTime());
        int end = Math.min(start + length, LAST_MINUTE);
        int onTime = Math.min(start + s.onTimeMinutes(), end);
        int late = Math.min(start + minutes(s.checkInOpens(), s.lateUntil() == null ? s.checkInCloses() : s.lateUntil()), end);
        int closes = Math.min(start + minutes(s.checkInOpens(), s.checkInCloses()), end);
        return new Times(time(start), time(end), time(start), time(onTime), time(late), time(closes));
    }

    /** จบตอนนี้ (อย่างน้อย 1 นาทีหลังเริ่ม) · ช่วงเช็คชื่อที่ยังเปิดอยู่ถูกตัดให้ปิดตอนจบ */
    static Times endPlan(SessionRow e, LocalTime nowTime) {
        int opens = mins(e.checkInOpens());
        int end = Math.min(Math.max(mins(nowTime.truncatedTo(ChronoUnit.MINUTES)), mins(e.startTime()) + 1), LAST_MINUTE);
        int closes = Math.max(opens, Math.min(mins(e.checkInCloses()), end));
        int late = Math.max(opens, Math.min(mins(e.lateUntil()), closes));
        int onTime = Math.max(opens, Math.min(mins(e.onTimeUntil()), late));
        return new Times(e.startTime(), time(end), e.checkInOpens(), time(onTime), time(late), time(closes));
    }

    private static ClassSessionInfo info(UUID sessionId, UUID sectionId, UUID roomId, LocalDate date, Times t,
                                         String status) {
        return new ClassSessionInfo(sessionId, sectionId, roomId, date, t.start(), t.end(), t.onTimeUntil(),
                t.lateUntil(), t.closes(), status);
    }

    private static int minutes(LocalTime from, LocalTime to) {
        return (int) Duration.between(from, to).toMinutes();
    }

    private static int mins(LocalTime t) {
        return t.toSecondOfDay() / 60;
    }

    private static LocalTime time(int minuteOfDay) {
        return LocalTime.of(minuteOfDay / 60, minuteOfDay % 60);
    }

    private static ResponseStatusException bad(String msg) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
    }

    private static ResponseStatusException conflict(String msg) {
        return new ResponseStatusException(HttpStatus.CONFLICT, msg);
    }
}
