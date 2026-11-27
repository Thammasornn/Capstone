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

    /**
     * startMode (เฉพาะเริ่มคาบ · จบคาบ = null): created = สร้างคาบใหม่ · rescheduled = ย้ายคาบวันนี้ที่ยังไม่ถึงเวลา / ยกเลิก
     * มาเริ่มตอนนี้ · reopened = เปิดคาบวันนี้ที่จบแล้วอีกครั้ง · moved = ย้ายคาบที่กำลังเรียนจากห้องอื่นมาห้องนี้
     * previousRoomCode = ห้องเดิมเมื่อคาบถูกย้ายห้อง (null = ห้องเดิม / คาบใหม่)
     */
    public record ClassSessionInfo(UUID sessionId, UUID sectionId, UUID roomId, LocalDate sessionDate,
                                   LocalTime startTime, LocalTime endTime, LocalTime onTimeUntil, LocalTime lateUntil,
                                   LocalTime checkInCloses, String sessionStatus, String startMode,
                                   String previousRoomCode) {}

    /** ผลการตัดสินว่าจะเริ่มคาบตอนนี้แบบไหน (ดู decide) */
    public enum StartMode {
        CREATED, RESCHEDULED, REOPENED, MOVED;

        public String json() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    private static final int LAST_MINUTE = 23 * 60 + 59;

    private final SessionControlRepository repo;
    private final AttendanceRepository attendance;
    private final RoomRepository rooms;
    private final AccessService access;
    private final AuditLogRepository audit;
    private final RoomEventHub hub;
    private final TransactionTemplate tx;
    private final ClassSessionRepository sessions;

    public SessionControlService(SessionControlRepository repo, AttendanceRepository attendance, RoomRepository rooms,
                                 AccessService access, AuditLogRepository audit, RoomEventHub hub,
                                 TransactionTemplate tx, ClassSessionRepository sessions) {
        this.sessions = sessions;
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
            StartMode mode;
            String action = "session.start_now";
            String before = null;
            int removedAbsents = 0;
            if (existing.isEmpty()) {
                mode = StartMode.CREATED;
                sessionId = repo.insertInProgress(sectionId, roomId, today, t);
            } else {
                SessionRow e = existing.get();
                mode = decide(e, roomId, now);
                if (mode == StartMode.RESCHEDULED && "cancelled".equals(e.sessionStatus())
                        && sessions.hasMakeup(e.sessionId())) {
                    throw conflict("คาบนี้ถูกยกเลิกและมีคาบชดเชยแล้ว ยกเลิกคาบชดเชยก่อน");
                }
                sessionId = e.sessionId();
                if (!e.roomId().equals(roomId)) previousRoom = e.roomId();
                before = "{\"room_id\": \"%s\", \"start_time\": \"%s\", \"end_time\": \"%s\", \"session_status\": \"%s\"}"
                        .formatted(e.roomId(), e.startTime(), e.endTime(), e.sessionStatus());
                if (mode == StartMode.REOPENED) {
                    // ขาดที่ระบบใส่ตอนปิดเช็คชื่อรอบก่อน → ลบ ให้กล้อง / job ลงผลใหม่ตามช่วงเช็คชื่อใหม่ · camera / manual / import เก็บไว้
                    removedAbsents = repo.deleteSystemAbsents(sessionId);
                    action = "session.reopen";
                } else if (mode == StartMode.MOVED) {
                    action = "session.move_room";
                }
                repo.moveToNow(sessionId, roomId, t);
            }
            String previousCode = previousRoom == null ? null : repo.roomCode(previousRoom).orElse(null);
            ClassSessionInfo info = new ClassSessionInfo(sessionId, sectionId, roomId, today, t.start(), t.end(),
                    t.onTimeUntil(), t.lateUntil(), t.closes(), "in_progress", mode.json(), previousCode);
            audit.log(access.user().userId(), action, "class_session", sessionId, before,
                    "{\"room_id\": \"%s\", \"start_time\": \"%s\", \"end_time\": \"%s\", \"start_mode\": \"%s\", \"removed_system_absents\": %d}"
                            .formatted(roomId, t.start(), t.end(), mode.json(), removedAbsents), ip);
            return new Result(info, previousRoom);
        });

        hub.publish(roomId, "session", new SessionEvent(r.info().sessionId(), "in_progress"));
        if (r.previousRoom() != null) {
            hub.publish(r.previousRoom(), "session", new SessionEvent(r.info().sessionId(), "in_progress"));
        }
        return r.info();
    }

    /**
     * คาบวันนี้ของกลุ่มเรียนมีอยู่แล้ว → เริ่มตอนนี้แบบไหน (ใช้สถานะตามเวลาจริง ไม่ใช่ค่าดิบใน DB · job อัปเดตช้าได้ ≤ 1 นาที)
     *  - cancelled / scheduled ที่ยังไม่ถึงเวลา → RESCHEDULED (ย้ายมาเริ่มตอนนี้)
     *  - กำลังเรียนจริง (เริ่มแล้ว ยังไม่เลยเวลาจบ) ห้องเดียวกัน → 409 · ห้องอื่น → MOVED
     *  - จบแล้ว (completed หรือเลยเวลาจบแต่ job ยังไม่เปลี่ยน) → REOPENED
     */
    static StartMode decide(SessionRow e, UUID roomId, LocalDateTime now) {
        String st = e.sessionStatus();
        if ("cancelled".equals(st)) return StartMode.RESCHEDULED;
        boolean started = !e.sessionDate().atTime(e.startTime()).isAfter(now);
        boolean ended = !e.sessionDate().atTime(e.endTime()).isAfter(now);
        if ("completed".equals(st) || (started && ended)) return StartMode.REOPENED;
        if (!started) return StartMode.RESCHEDULED;       // scheduled ยังไม่ถึงเวลา
        if (e.roomId().equals(roomId)) throw conflict("กลุ่มเรียนนี้มีคาบที่กำลังเรียนอยู่แล้วในห้องนี้");
        return StartMode.MOVED;
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
                t.lateUntil(), t.closes(), status, null, null);
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
