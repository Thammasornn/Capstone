package com.faculty.attendance.attendance;

import com.faculty.attendance.attendance.ClassSessionRepository.SectionRef;
import com.faculty.attendance.attendance.ClassSessionRepository.SessionItem;
import com.faculty.attendance.attendance.ClassSessionRepository.SessionValues;
import com.faculty.attendance.audit.AuditLogRepository;
import com.faculty.attendance.auth.AccessService;
import com.faculty.attendance.common.Times;
import com.faculty.attendance.course.CourseRepository;
import com.faculty.attendance.room.RoomDtos.SessionEvent;
import com.faculty.attendance.room.RoomEventHub;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

/**
 * "คาบเรียน" — จัดการคาบจริงรายคาบ (คลาสออนไซต์) · sections = แม่แบบตารางเรียน (PUT /courses/{id})
 * คาบที่ยังไม่เริ่ม: เลื่อน / แก้ (PATCH) · ยกเลิก (+ ชดเชยในคำขอเดียว) · คืนคาบที่ยกเลิก · เพิ่มคาบชดเชย / คาบพิเศษ
 * คาบที่กำลังเรียน / จบแล้ว: ปรับเวลาด้วย SessionAdjustService (reanchor / extend / check-in-window)
 * ทุกการเปลี่ยนแปลง: reason บังคับ · transaction + lock แถวคาบ · audit · SSE session ถ้าคาบเป็นของวันนี้
 */
@Service
public class ClassSessionService {

    private static final int REASON_MAX = 500;
    private static final int ADJUST_DAYS = 7;

    public record SessionBody(String sessionDate, String startTime, String endTime, String checkInOpens,
                              String onTimeUntil, String lateUntil, String checkInCloses, UUID roomId,
                              UUID makeupForSessionId, String reason) {}

    public record CancelBody(String reason, SessionBody makeup) {}

    public record Attendance(int onTime, int late, int absent, int noRecord) {}

    /**
     * sessionStatus = สถานะตามเวลาจริง (job อัปเดตใน DB ช้าได้ ≤ 1 นาที) · makeupSessionId = คาบชดเชยของคาบนี้ (ถ้ามี)
     * allowedActions: reschedule | cancel | restore | adjust | report
     */
    public record SessionView(UUID sessionId, UUID sectionId, int sessionNo, LocalDate sessionDate,
                              LocalDate originalDate, String startTime, String endTime, String checkInOpens,
                              String onTimeUntil, String lateUntil, String checkInCloses, UUID roomId,
                              String roomCode, String sessionStatus, boolean isOverridden, boolean isExtra,
                              UUID makeupForSessionId, LocalDate makeupForDate, UUID makeupSessionId,
                              String cancelReason, Attendance attendance, List<String> allowedActions) {}

    public record SectionSessions(UUID sectionId, LocalDate semesterStart, LocalDate semesterEnd,
                                  List<SessionView> sessions) {}

    public record CancelResult(SessionView session, int keptAttendanceCount, SessionView makeup) {}

    private final ClassSessionRepository repo;
    private final CourseRepository courses;
    private final AccessService access;
    private final AuditLogRepository audit;
    private final RoomEventHub hub;
    private final TransactionTemplate tx;
    private final JsonMapper json;

    public ClassSessionService(ClassSessionRepository repo, CourseRepository courses, AccessService access,
                               AuditLogRepository audit, RoomEventHub hub, TransactionTemplate tx, JsonMapper json) {
        this.repo = repo;
        this.courses = courses;
        this.access = access;
        this.audit = audit;
        this.hub = hub;
        this.tx = tx;
        this.json = json;
    }

    // ---------- 1. list ----------

    public SectionSessions list(UUID sectionId) {
        access.requireSectionStaff(sectionId);
        SectionRef sec = section(sectionId);
        List<SessionItem> items = repo.listBySection(sectionId);
        Map<UUID, UUID> makeupOf = new LinkedHashMap<>();
        for (SessionItem i : items) {
            if (i.makeupForSessionId() != null && !"cancelled".equals(i.sessionStatus())) {
                makeupOf.put(i.makeupForSessionId(), i.sessionId());
            }
        }
        LocalDateTime now = Times.now();
        List<SessionView> out = new ArrayList<>();
        for (SessionItem i : items) out.add(view(i, makeupOf.get(i.sessionId()), now));
        return new SectionSessions(sectionId, sec.semesterStart(), sec.semesterEnd(), out);
    }

    // ---------- 2. reschedule ----------

    public SessionView reschedule(UUID sessionId, SessionBody body, String ip) {
        if (body == null) throw bad("ข้อมูลไม่ถูกต้อง");
        String reason = reason(body.reason());
        LocalDateTime now = Times.now();
        record R(SessionItem before, SessionItem after) {}
        R r = tx.execute(t -> {
            SessionItem s = lock(sessionId);
            access.requireSectionStaff(s.sectionId());
            if (!"scheduled".equals(status(s, now)) || !start(s).isAfter(now)) {
                throw conflict("คาบนี้เริ่มแล้ว ใช้เมนูจัดการคาบที่หน้าห้อง");
            }
            SessionValues v = merge(s, body);
            if (!v.sessionDate().atTime(v.startTime()).isAfter(now)) {
                throw bad("เวลาเริ่มต้องอยู่ในอนาคต ถ้าจะเริ่มตอนนี้ใช้ปุ่มเริ่มคาบที่หน้าห้อง");
            }
            validate(v, new SectionRef(s.sectionId(), s.semesterStart(), s.semesterEnd()));
            if (!v.sessionDate().equals(s.sessionDate()) && repo.existsOnDate(s.sectionId(), v.sessionDate(), sessionId)) {
                throw conflict("วันนี้กลุ่มเรียนนี้มีคาบอยู่แล้ว (1 กลุ่มเรียนมีได้ 1 คาบต่อวัน)");
            }
            checkRoom(v, sessionId);
            repo.reschedule(sessionId, v);
            SessionItem after = repo.lock(sessionId).orElseThrow();
            audit.log(access.user().userId(), "session.reschedule", "class_session", sessionId,
                    toJson(snapshot(s, null)), toJson(snapshot(after, reason)), ip);
            return new R(s, after);
        });
        publishIfToday(r.before());
        if (!r.after().roomId().equals(r.before().roomId()) || !r.after().sessionDate().equals(r.before().sessionDate())) {
            publishIfToday(r.after());
        }
        return view(r.after(), null, Times.now());
    }

    // ---------- 3. cancel (+ make-up) ----------

    public CancelResult cancel(UUID sessionId, CancelBody body, String ip) {
        if (body == null) throw bad("ข้อมูลไม่ถูกต้อง");
        String reason = reason(body.reason());
        LocalDateTime now = Times.now();
        record R(SessionItem before, SessionItem after, SessionItem makeup) {}
        R r = tx.execute(t -> {
            SessionItem s = lock(sessionId);
            access.requireSectionStaff(s.sectionId());
            String st = status(s, now);
            if ("completed".equals(st)) throw conflict("คาบนี้จบไปแล้ว ยกเลิกไม่ได้");
            if ("cancelled".equals(st)) throw conflict("คาบนี้ถูกยกเลิกไปแล้ว");
            UUID actor = access.user().userId();
            repo.cancel(sessionId, reason);
            SessionItem after = repo.lock(sessionId).orElseThrow();
            audit.log(actor, "session.cancel", "class_session", sessionId, toJson(snapshot(s, null)),
                    toJson(snapshot(after, reason)), ip);
            SessionItem makeup = null;
            if (body.makeup() != null) {
                makeup = createExtra(s.sectionId(), body.makeup(), sessionId, reason, now, ip);
            }
            return new R(s, after, makeup);
        });
        publishIfToday(r.after());
        if (r.makeup() != null) publishIfToday(r.makeup());
        int kept = r.before().onTime() + r.before().late() + r.before().absent();
        LocalDateTime n = Times.now();
        return new CancelResult(view(r.after(), r.makeup() == null ? null : r.makeup().sessionId(), n), kept,
                r.makeup() == null ? null : view(r.makeup(), null, n));
    }

    // ---------- 4. extra / make-up ----------

    public SessionView createExtra(UUID sectionId, SessionBody body, String ip) {
        if (body == null) throw bad("ข้อมูลไม่ถูกต้อง");
        access.requireSectionStaff(sectionId);
        String reason = reason(body.reason());
        LocalDateTime now = Times.now();
        SessionItem created = tx.execute(t -> {
            UUID makeupFor = body.makeupForSessionId();
            if (makeupFor != null) {
                SessionItem orig = repo.lock(makeupFor)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "ไม่พบคาบที่จะชดเชย"));
                if (!orig.sectionId().equals(sectionId)) throw bad("คาบที่จะชดเชยต้องเป็นของกลุ่มเรียนเดียวกัน");
                if (!"cancelled".equals(orig.sessionStatus())) {
                    throw conflict("ต้องยกเลิกคาบเดิมก่อน หรือใช้ปุ่มยกเลิก + ชดเชย");
                }
            }
            return createExtra(sectionId, body, makeupFor, reason, now, ip);
        });
        publishIfToday(created);
        return view(created, null, Times.now());
    }

    /** สร้างคาบเพิ่ม (ใช้ทั้งข้อ 3 และ 4) · เรียกภายใน transaction */
    private SessionItem createExtra(UUID sectionId, SessionBody b, UUID makeupFor, String reason, LocalDateTime now,
                                    String ip) {
        SectionRef sec = section(sectionId);
        if (makeupFor != null && repo.hasMakeup(makeupFor)) throw conflict("คาบนี้มีคาบชดเชยแล้ว");
        SessionValues v = full(b);
        if (!v.sessionDate().atTime(v.startTime()).isAfter(now)) {
            throw bad("เวลาเริ่มของคาบชดเชย / คาบพิเศษต้องอยู่ในอนาคต ถ้าจะเริ่มตอนนี้ใช้ปุ่มเริ่มคาบที่หน้าห้อง");
        }
        validate(v, sec);
        if (repo.existsOnDate(sectionId, v.sessionDate(), null)) {
            throw conflict("วันนี้กลุ่มเรียนนี้มีคาบอยู่แล้ว (1 กลุ่มเรียนมีได้ 1 คาบต่อวัน)");
        }
        checkRoom(v, null);
        UUID id = repo.insertExtra(sectionId, v, makeupFor);
        SessionItem created = repo.lock(id).orElseThrow();
        audit.log(access.user().userId(), "session.makeup_create", "class_session", id, null,
                toJson(snapshot(created, reason)), ip);
        return created;
    }

    // ---------- 5. restore ----------

    public SessionView restore(UUID sessionId, CancelBody body, String ip) {
        String reason = reason(body == null ? null : body.reason());
        LocalDateTime now = Times.now();
        SessionItem after = tx.execute(t -> {
            SessionItem s = lock(sessionId);
            access.requireSectionStaff(s.sectionId());
            if (!"cancelled".equals(s.sessionStatus())) throw conflict("คาบนี้ไม่ได้ถูกยกเลิก");
            if (!start(s).isAfter(now)) throw conflict("คืนได้เฉพาะคาบที่ยังไม่ถึงเวลาเริ่ม");
            if (repo.hasMakeup(sessionId)) throw conflict("มีคาบชดเชยของคาบนี้แล้ว ยกเลิกคาบชดเชยก่อน");
            if (repo.existsOnDate(s.sectionId(), s.sessionDate(), sessionId)) {
                throw conflict("วันนี้กลุ่มเรียนนี้มีคาบอยู่แล้ว (1 กลุ่มเรียนมีได้ 1 คาบต่อวัน)");
            }
            checkRoom(values(s), sessionId);
            repo.restore(sessionId);
            SessionItem a = repo.lock(sessionId).orElseThrow();
            audit.log(access.user().userId(), "session.restore", "class_session", sessionId,
                    toJson(snapshot(s, null)), toJson(snapshot(a, reason)), ip);
            return a;
        });
        publishIfToday(after);
        return view(after, null, Times.now());
    }

    // ---------- rules ----------

    /** สถานะตามเวลาจริง (เหมือน SessionAdjustService.effectiveStatus) */
    static String status(SessionItem s, LocalDateTime now) {
        String st = s.sessionStatus();
        if (!"scheduled".equals(st) && !"in_progress".equals(st)) return st;
        if (start(s).isAfter(now)) return "scheduled";
        if (!s.sessionDate().atTime(s.endTime()).isAfter(now)) return "completed";
        return "in_progress";
    }

    static List<String> allowedActions(SessionItem s, boolean hasMakeup, LocalDateTime now) {
        return switch (status(s, now)) {
            case "scheduled" -> List.of("reschedule", "cancel");
            case "in_progress" -> List.of("cancel", "adjust");
            case "completed" -> s.sessionDate().isBefore(now.toLocalDate().minusDays(ADJUST_DAYS))
                    ? List.of("report") : List.of("report", "adjust");
            case "cancelled" -> start(s).isAfter(now) && !hasMakeup ? List.of("restore") : List.of();
            default -> List.of();
        };
    }

    /** start < end · start ≤ เปิด ≤ ตรงเวลา ≤ สาย ≤ ปิด ≤ end · วันที่อยู่ในภาคเรียน · ห้องมีอยู่ */
    private void validate(SessionValues v, SectionRef sec) {
        if (!v.endTime().isAfter(v.startTime())) throw bad("เวลาเลิกเรียนต้องหลังเวลาเริ่ม");
        if (v.checkInOpens().isBefore(v.startTime())) throw bad("เวลาเปิดเช็คชื่อต้องไม่ก่อนเวลาเริ่มเรียน");
        if (v.onTimeUntil().isBefore(v.checkInOpens())) throw bad("เวลาตรงเวลาถึงต้องไม่ก่อนเวลาเปิดเช็คชื่อ");
        if (v.lateUntil().isBefore(v.onTimeUntil())) throw bad("เวลาสายถึงต้องไม่ก่อนเวลาตรงเวลาถึง");
        if (v.checkInCloses().isBefore(v.lateUntil())) throw bad("เวลาปิดเช็คชื่อต้องไม่ก่อนเวลาสายถึง");
        if (v.checkInCloses().isAfter(v.endTime())) throw bad("เวลาปิดเช็คชื่อต้องไม่เกินเวลาเลิกเรียน");
        if (v.sessionDate().isBefore(sec.semesterStart()) || v.sessionDate().isAfter(sec.semesterEnd())) {
            throw bad("วันที่ต้องอยู่ในภาคเรียนของกลุ่มเรียน (" + sec.semesterStart() + " ถึง " + sec.semesterEnd() + ")");
        }
        if (courses.roomCode(v.roomId()).isEmpty()) throw bad("ไม่พบห้องเรียน");
    }

    private void checkRoom(SessionValues v, UUID exclude) {
        repo.roomConflict(v.roomId(), v.sessionDate(), v.startTime(), v.endTime(), exclude).ifPresent(other -> {
            throw conflict("ห้อง " + courses.roomCode(v.roomId()).orElse("") + " มีคาบ " + other + " ในช่วงเวลานี้แล้ว");
        });
    }

    /** PATCH: ส่งเฉพาะที่เปลี่ยน ที่ไม่ส่งใช้ค่าเดิม */
    private static SessionValues merge(SessionItem s, SessionBody b) {
        return new SessionValues(
                b.sessionDate() == null ? s.sessionDate() : date(b.sessionDate()),
                b.startTime() == null ? s.startTime() : time(b.startTime(), "startTime"),
                b.endTime() == null ? s.endTime() : time(b.endTime(), "endTime"),
                b.checkInOpens() == null ? s.checkInOpens() : time(b.checkInOpens(), "checkInOpens"),
                b.onTimeUntil() == null ? s.onTimeUntil() : time(b.onTimeUntil(), "onTimeUntil"),
                b.lateUntil() == null ? s.lateUntil() : time(b.lateUntil(), "lateUntil"),
                b.checkInCloses() == null ? s.checkInCloses() : time(b.checkInCloses(), "checkInCloses"),
                b.roomId() == null ? s.roomId() : b.roomId());
    }

    /** คาบใหม่: ต้องส่งครบทุกค่า */
    private static SessionValues full(SessionBody b) {
        if (b.sessionDate() == null || b.startTime() == null || b.endTime() == null || b.checkInOpens() == null
                || b.onTimeUntil() == null || b.lateUntil() == null || b.checkInCloses() == null || b.roomId() == null) {
            throw bad("คาบใหม่ต้องระบุ sessionDate, startTime, endTime, checkInOpens, onTimeUntil, lateUntil, "
                    + "checkInCloses และ roomId");
        }
        return new SessionValues(date(b.sessionDate()), time(b.startTime(), "startTime"), time(b.endTime(), "endTime"),
                time(b.checkInOpens(), "checkInOpens"), time(b.onTimeUntil(), "onTimeUntil"),
                time(b.lateUntil(), "lateUntil"), time(b.checkInCloses(), "checkInCloses"), b.roomId());
    }

    private static SessionValues values(SessionItem s) {
        return new SessionValues(s.sessionDate(), s.startTime(), s.endTime(), s.checkInOpens(), s.onTimeUntil(),
                s.lateUntil(), s.checkInCloses(), s.roomId());
    }

    // ---------- helpers ----------

    private SessionItem lock(UUID sessionId) {
        return repo.lock(sessionId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "ไม่พบคาบเรียน"));
    }

    private SectionRef section(UUID sectionId) {
        return repo.section(sectionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "ไม่พบกลุ่มเรียน"));
    }

    private void publishIfToday(SessionItem s) {
        if (s.sessionDate().equals(Times.now().toLocalDate())) {
            hub.publish(s.roomId(), "session", new SessionEvent(s.sessionId(), s.sessionStatus()));
        }
    }

    private static LocalDateTime start(SessionItem s) {
        return s.sessionDate().atTime(s.startTime());
    }

    private static SessionView view(SessionItem i, UUID makeupSessionId, LocalDateTime now) {
        return new SessionView(i.sessionId(), i.sectionId(), i.sessionNo(), i.sessionDate(), i.originalDate(),
                hhmm(i.startTime()), hhmm(i.endTime()), hhmm(i.checkInOpens()), hhmm(i.onTimeUntil()),
                hhmm(i.lateUntil()), hhmm(i.checkInCloses()), i.roomId(), i.roomCode(), status(i, now),
                i.isOverridden(), i.isExtra(), i.makeupForSessionId(), i.makeupForDate(), makeupSessionId,
                i.cancelReason(), new Attendance(i.onTime(), i.late(), i.absent(), i.noRecord()),
                allowedActions(i, makeupSessionId != null, now));
    }

    private static Map<String, Object> snapshot(SessionItem s, String reason) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("session_date", s.sessionDate().toString());
        m.put("start_time", hhmm(s.startTime()));
        m.put("end_time", hhmm(s.endTime()));
        m.put("check_in_opens", hhmm(s.checkInOpens()));
        m.put("on_time_until", hhmm(s.onTimeUntil()));
        m.put("late_until", hhmm(s.lateUntil()));
        m.put("check_in_closes", hhmm(s.checkInCloses()));
        m.put("room_id", s.roomId().toString());
        m.put("session_status", s.sessionStatus());
        if (s.makeupForSessionId() != null) m.put("makeup_for_session_id", s.makeupForSessionId().toString());
        if (reason != null) m.put("reason", reason);
        return m;
    }

    private static String reason(String raw) {
        String r = raw == null ? "" : raw.trim();
        if (r.isEmpty()) throw bad("กรุณาระบุเหตุผล");
        if (r.length() > REASON_MAX) throw bad("เหตุผลยาวได้ไม่เกิน " + REASON_MAX + " ตัวอักษร");
        return r;
    }

    private static LocalDate date(String raw) {
        try {
            return LocalDate.parse(raw.trim());
        } catch (DateTimeParseException e) {
            throw bad("sessionDate ต้องเป็นรูปแบบ yyyy-MM-dd");
        }
    }

    private static LocalTime time(String raw, String field) {
        String v = raw.trim();
        if (!v.matches("^\\d{2}:\\d{2}$")) throw bad(field + " ต้องเป็นเวลารูปแบบ HH:mm");
        try {
            return LocalTime.parse(v).truncatedTo(ChronoUnit.MINUTES);
        } catch (DateTimeParseException e) {
            throw bad(field + " ต้องเป็นเวลารูปแบบ HH:mm");
        }
    }

    private static String hhmm(LocalTime t) {
        return "%02d:%02d".formatted(t.getHour(), t.getMinute());
    }

    private String toJson(Object o) {
        return json.writeValueAsString(o);
    }

    private static ResponseStatusException bad(String msg) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
    }

    private static ResponseStatusException conflict(String msg) {
        return new ResponseStatusException(HttpStatus.CONFLICT, msg);
    }
}
