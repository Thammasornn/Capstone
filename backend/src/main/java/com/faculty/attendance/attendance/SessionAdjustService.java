package com.faculty.attendance.attendance;

import com.faculty.attendance.attendance.SessionAdjustRepository.AttendanceRow;
import com.faculty.attendance.attendance.SessionAdjustRepository.LockedSession;
import com.faculty.attendance.attendance.SessionControlRepository.Times;
import com.faculty.attendance.audit.AuditLogRepository;
import com.faculty.attendance.auth.AccessService;
import com.faculty.attendance.room.RoomDtos.CheckInEvent;
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
 * ปรับเวลาของคาบที่เริ่มไปแล้ว (คาบเดียว ไม่แตะตารางเรียนของกลุ่มเรียน) · admin หรืออาจารย์ที่สอนกลุ่มนั้น
 *
 *  - reanchor: อาจารย์เข้าสาย / เริ่มจริงช้ากว่าตาราง → เลื่อนเวลาเริ่ม + ช่วงเช็คชื่อทั้งชุดเท่ากัน (เวลาเลิกคงเดิม)
 *  - extend: เลิกช้า / ปรับเวลาจบ
 *  - check-in-window: ปรับช่วงตรงเวลา / สาย / ปิดเช็คชื่อ (check_in_opens คงเดิม)
 *
 * reanchor + check-in-window คิดผลเช็คชื่อใหม่ตามกฎ "ไม่ลงโทษย้อนหลัง":
 *  manual ไม่เปลี่ยน · camera คิดใหม่จาก check_in_time (ดีขึ้น = ใช้เลย, แย่ลง = ใช้เมื่อ applyDowngrades) ·
 *  system (absent) ลบทิ้งเมื่อคาบยังเรียนอยู่และช่วงเช็คชื่อใหม่ยังไม่ปิด (กล้องเช็คชื่อได้อีก)
 * dryRun = true คืนผลแบบเดียวกันแต่ไม่เขียน DB (Preview & Confirm)
 */
@Service
public class SessionAdjustService {

    public record ReanchorRequest(String actualStart, String reason, Boolean dryRun, Boolean applyDowngrades) {}

    public record ExtendRequest(String endTime, String reason, Boolean dryRun) {}

    public record CheckInWindowRequest(String onTimeUntil, String lateUntil, String checkInCloses, String reason,
                                       Boolean dryRun, Boolean applyDowngrades) {}

    public record TimesView(String startTime, String endTime, String checkInOpens, String onTimeUntil,
                            String lateUntil, String checkInCloses) {}

    public record Change(UUID studentId, String studentCode, String studentName, String source,
                         LocalDateTime checkInTime, String fromStatus, String toStatus, String kind,
                         boolean applied) {}

    public record Summary(int upgrades, int downgrades, boolean downgradesApplied, int reopened, int unchanged) {}

    public record AdjustResponse(UUID sessionId, boolean dryRun, TimesView before, TimesView after,
                                 List<Change> changes, Summary summary) {}

    private static final int COMPLETED_EDIT_DAYS = 7;
    private static final int REANCHOR_EARLIEST_MINUTES = 60;

    private final SessionAdjustRepository repo;
    private final AccessService access;
    private final AuditLogRepository audit;
    private final RoomEventHub hub;
    private final TransactionTemplate tx;
    private final JsonMapper json;

    public SessionAdjustService(SessionAdjustRepository repo, AccessService access, AuditLogRepository audit,
                                RoomEventHub hub, TransactionTemplate tx, JsonMapper json) {
        this.repo = repo;
        this.access = access;
        this.audit = audit;
        this.hub = hub;
        this.tx = tx;
        this.json = json;
    }

    private enum Kind { REANCHOR, EXTEND, WINDOW }

    /** ผลภายใน transaction + สิ่งที่ต้องส่ง event หลัง commit */
    private record Outcome(AdjustResponse response, UUID roomId, String status, List<CheckInEvent> events) {}

    // ---------- endpoints ----------

    public AdjustResponse reanchor(UUID sessionId, ReanchorRequest in, String ip) {
        if (in == null) throw bad("ข้อมูลไม่ถูกต้อง");
        String reason = reason(in.reason());
        LocalTime actual = hhmm(in.actualStart(), "actualStart (เวลาเริ่มจริง)");
        return run(sessionId, Kind.REANCHOR, reason, bool(in.dryRun()), bool(in.applyDowngrades()), ip,
                (s, status, now) -> {
                    Times t = s.times();
                    int start = mins(t.start());
                    int a = mins(actual);
                    if (a < start - REANCHOR_EARLIEST_MINUTES) {
                        throw bad("เวลาเริ่มจริงเร็วกว่าเวลาเริ่มเดิมได้ไม่เกิน " + REANCHOR_EARLIEST_MINUTES + " นาที");
                    }
                    if ("in_progress".equals(status) && s.sessionDate().atTime(actual).isAfter(now)) {
                        throw bad("เวลาเริ่มจริงต้องไม่เกินเวลาปัจจุบัน");
                    }
                    if (a >= mins(t.end())) throw bad("เวลาเริ่มจริงต้องก่อนเวลาเลิกเรียน (" + hhmm(t.end()) + ")");
                    int delta = a - start;
                    int end = mins(t.end());
                    int opens = mins(t.opens()) + delta;
                    if (opens >= end) throw bad("เลื่อนแล้วช่วงเช็คชื่อเริ่มหลังเวลาเลิกเรียน กรุณาปรับเวลาเลิกเรียนก่อน");
                    int closes = Math.min(mins(t.closes()) + delta, end);
                    int late = Math.min(mins(t.lateUntil()) + delta, closes);
                    int onTime = Math.min(mins(t.onTimeUntil()) + delta, late);
                    Times next = new Times(actual, t.end(), time(opens), time(onTime), time(late), time(closes));
                    if (delta < 0) checkRoom(s, next);
                    return next;
                });
    }

    public AdjustResponse extend(UUID sessionId, ExtendRequest in, String ip) {
        if (in == null) throw bad("ข้อมูลไม่ถูกต้อง");
        String reason = reason(in.reason());
        LocalTime endTime = hhmm(in.endTime(), "endTime (เวลาเลิกเรียน)");
        return run(sessionId, Kind.EXTEND, reason, bool(in.dryRun()), false, ip, (s, status, now) -> {
            Times t = s.times();
            if (s.sessionDate().atTime(endTime).isBefore(now.truncatedTo(ChronoUnit.MINUTES).plusMinutes(1))) {
                throw bad("เวลาเลิกเรียนต้องหลังเวลาปัจจุบันอย่างน้อย 1 นาที");
            }
            if (endTime.isBefore(t.closes())) {
                throw bad("เวลาเลิกเรียนต้องไม่ก่อนเวลาปิดเช็คชื่อ (" + hhmm(t.closes()) + ") — ปรับช่วงเช็คชื่อก่อน");
            }
            Times next = new Times(t.start(), endTime, t.opens(), t.onTimeUntil(), t.lateUntil(), t.closes());
            checkRoom(s, next);
            return next;
        });
    }

    public AdjustResponse checkInWindow(UUID sessionId, CheckInWindowRequest in, String ip) {
        if (in == null) throw bad("ข้อมูลไม่ถูกต้อง");
        String reason = reason(in.reason());
        LocalTime onTime = hhmm(in.onTimeUntil(), "onTimeUntil (ตรงเวลาถึง)");
        LocalTime late = hhmm(in.lateUntil(), "lateUntil (สายถึง)");
        LocalTime closes = hhmm(in.checkInCloses(), "checkInCloses (ปิดเช็คชื่อ)");
        return run(sessionId, Kind.WINDOW, reason, bool(in.dryRun()), bool(in.applyDowngrades()), ip,
                (s, status, now) -> new Times(s.times().start(), s.times().end(), s.times().opens(), onTime, late,
                        closes));
    }

    // ---------- core ----------

    @FunctionalInterface
    private interface Planner {
        Times plan(LockedSession s, String effectiveStatus, LocalDateTime now);
    }

    private AdjustResponse run(UUID sessionId, Kind kind, String reason, boolean dryRun, boolean applyDowngrades,
                               String ip, Planner planner) {
        LocalDateTime now = com.faculty.attendance.common.Times.now().withNano(0);
        Outcome out = tx.execute(txs -> {
            LockedSession s = repo.lockSession(sessionId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "ไม่พบคาบเรียน"));
            access.requireSectionStaff(s.sectionId());
            String status = effectiveStatus(s, now);
            checkAllowed(kind, s, status, now.toLocalDate());

            Times next = planner.plan(s, status, now);
            validate(next);

            List<Change> changes = new ArrayList<>();
            List<AttendanceRow> toUpdate = new ArrayList<>();
            List<AttendanceRow> toReopen = new ArrayList<>();
            int up = 0, down = 0, reopened = 0, unchanged = 0;
            if (kind != Kind.EXTEND) {
                boolean canReopen = "in_progress".equals(status) && s.sessionDate().atTime(next.closes()).isAfter(now);
                for (AttendanceRow r : repo.lockRecords(sessionId)) {
                    LocalDateTime checkIn = com.faculty.attendance.common.Times.local(r.checkInTime());
                    String to = null;
                    String changeKind = null;
                    boolean applied = false;
                    if ("camera".equals(r.source()) && checkIn != null) {
                        String calc = !checkIn.isAfter(s.sessionDate().atTime(next.onTimeUntil())) ? "on_time"
                                : !checkIn.isAfter(s.sessionDate().atTime(next.lateUntil())) ? "late"
                                : r.status();
                        if (!calc.equals(r.status())) {
                            to = calc;
                            boolean better = rank(calc) < rank(r.status());
                            changeKind = better ? "upgrade" : "downgrade";
                            applied = better || applyDowngrades;
                            if (better) up++; else down++;
                            if (applied) toUpdate.add(r);
                        }
                    } else if ("system".equals(r.source()) && canReopen) {
                        changeKind = "reopen";
                        applied = true;
                        reopened++;
                        toReopen.add(r);
                    }
                    if (changeKind == null) {
                        unchanged++;
                        continue;
                    }
                    changes.add(new Change(r.studentId(), r.studentCode(), r.studentName(), r.source(), checkIn,
                            r.status(), to, changeKind, applied));
                }
            }
            Summary summary = new Summary(up, down, applyDowngrades && down > 0, reopened, unchanged);
            AdjustResponse resp = new AdjustResponse(sessionId, dryRun, view(s.times()), view(next), changes,
                    summary);
            if (dryRun) {
                return new Outcome(resp, s.roomId(), status, List.of());
            }

            repo.updateTimes(sessionId, next);
            UUID actor = access.user().userId();
            Map<String, Object> after = new LinkedHashMap<>(snapshot(next));
            after.put("reason", reason);
            after.put("summary", summary);
            audit.log(actor, action(kind), "class_session", sessionId, toJson(snapshot(s.times())), toJson(after),
                    ip);

            List<CheckInEvent> events = new ArrayList<>();
            for (Change c : changes) {
                if (!c.applied()) continue;
                AttendanceRow r = "reopen".equals(c.kind())
                        ? toReopen.stream().filter(x -> x.studentId().equals(c.studentId())).findFirst().orElseThrow()
                        : toUpdate.stream().filter(x -> x.studentId().equals(c.studentId())).findFirst().orElseThrow();
                Map<String, Object> rowBefore = rowSnapshot(c.fromStatus(), c.source(), c.checkInTime());
                Map<String, Object> rowAfter;
                if ("reopen".equals(c.kind())) {
                    repo.deleteSystemAbsent(r.attendanceId());
                    rowAfter = new LinkedHashMap<>();
                    rowAfter.put("deleted", true);
                } else {
                    repo.updateCameraStatus(r.attendanceId(), c.toStatus());
                    rowAfter = rowSnapshot(c.toStatus(), c.source(), c.checkInTime());
                    events.add(new CheckInEvent(sessionId, c.studentId(), c.studentCode(), c.checkInTime(),
                            c.toStatus(), c.source()));
                }
                rowAfter.put("by", action(kind));
                rowAfter.put("reason", reason);
                audit.log(actor, "attendance.recalculated", "attendance_record", r.attendanceId(),
                        toJson(rowBefore), toJson(rowAfter), ip);
            }
            return new Outcome(resp, s.roomId(), status, events);
        });

        if (!dryRun) {
            hub.publish(out.roomId(), "session", new SessionEvent(sessionId, out.status()));
            for (CheckInEvent e : out.events()) hub.publish(out.roomId(), "check_in", e);
        }
        return out.response();
    }

    /**
     * สถานะจริงตามเวลา (job เลื่อน session_status ทุก 1 นาที ค่าใน DB อาจช้ากว่าเวลาจริงไม่เกิน 1 นาที)
     * scheduled ที่ถึงเวลาเริ่มแล้ว = in_progress · in_progress ที่เลยเวลาเลิกแล้ว = completed
     */
    static String effectiveStatus(LockedSession s, LocalDateTime now) {
        String st = s.sessionStatus();
        if (!"scheduled".equals(st) && !"in_progress".equals(st)) return st;
        if (s.sessionDate().atTime(s.times().start()).isAfter(now)) return "scheduled";
        if (!s.sessionDate().atTime(s.times().end()).isAfter(now)) return "completed";
        return "in_progress";
    }

    private static void checkAllowed(Kind kind, LockedSession s, String status, LocalDate today) {
        switch (status) {
            case "scheduled" -> throw conflict("คาบนี้ยังไม่เริ่ม ใช้การแก้ตารางเรียนหรือเริ่มคาบตอนนี้");
            case "cancelled" -> throw conflict("คาบนี้ถูกยกเลิกแล้ว");
            case "completed" -> {
                if (kind == Kind.EXTEND) throw conflict("คาบนี้จบไปแล้ว ขยายเวลาเลิกเรียนไม่ได้");
                if (s.sessionDate().isBefore(today.minusDays(COMPLETED_EDIT_DAYS))) {
                    throw conflict("แก้เวลาคาบที่จบแล้วได้ภายใน " + COMPLETED_EDIT_DAYS
                            + " วันเท่านั้น — ใช้การเช็คชื่อด้วยมือแทน");
                }
            }
            default -> { }   // in_progress
        }
    }

    /** start < end · start ≤ เปิด ≤ ตรงเวลา ≤ สาย ≤ ปิด ≤ end (ตรวจเองก่อนเขียน DB) */
    static void validate(Times t) {
        if (!t.end().isAfter(t.start())) throw bad("เวลาเลิกเรียนต้องหลังเวลาเริ่ม");
        if (t.opens().isBefore(t.start())) throw bad("เวลาเปิดเช็คชื่อต้องไม่ก่อนเวลาเริ่มเรียน");
        if (t.onTimeUntil().isBefore(t.opens())) throw bad("เวลาตรงเวลาถึงต้องไม่ก่อนเวลาเปิดเช็คชื่อ (" + hhmm(t.opens()) + ")");
        if (t.lateUntil().isBefore(t.onTimeUntil())) throw bad("เวลาสายถึงต้องไม่ก่อนเวลาตรงเวลาถึง");
        if (t.closes().isBefore(t.lateUntil())) throw bad("เวลาปิดเช็คชื่อต้องไม่ก่อนเวลาสายถึง");
        if (t.closes().isAfter(t.end())) throw bad("เวลาปิดเช็คชื่อต้องไม่เกินเวลาเลิกเรียน (" + hhmm(t.end()) + ")");
    }

    private void checkRoom(LockedSession s, Times next) {
        repo.roomConflict(s.roomId(), s.sessionDate(), next.start(), next.end(), s.sessionId()).ifPresent(other -> {
            throw conflict("ห้องนี้มีคาบ " + other + " ในช่วงเวลานี้แล้ว");
        });
    }

    // ---------- helpers ----------

    private static String action(Kind kind) {
        return switch (kind) {
            case REANCHOR -> "session.reanchor";
            case EXTEND -> "session.extend";
            case WINDOW -> "session.check_in_window";
        };
    }

    private static int rank(String status) {
        return switch (status) {
            case "on_time" -> 0;
            case "late" -> 1;
            default -> 2;
        };
    }

    private static String reason(String raw) {
        String r = raw == null ? "" : raw.trim();
        if (r.isEmpty()) throw bad("กรุณาระบุเหตุผล");
        if (r.length() > 500) throw bad("เหตุผลยาวได้ไม่เกิน 500 ตัวอักษร");
        return r;
    }

    private static boolean bool(Boolean b) {
        return b != null && b;
    }

    /** "HH:mm" (รับ "HH:mm:ss" ได้ ตัดวินาทีทิ้ง) */
    static LocalTime hhmm(String raw, String field) {
        if (raw == null || raw.isBlank()) throw bad("กรุณาระบุ " + field);
        try {
            return LocalTime.parse(raw.trim()).truncatedTo(ChronoUnit.MINUTES);
        } catch (DateTimeParseException e) {
            throw bad(field + " ต้องเป็นเวลารูปแบบ HH:mm");
        }
    }

    private static String hhmm(LocalTime t) {
        return "%02d:%02d".formatted(t.getHour(), t.getMinute());
    }

    private static TimesView view(Times t) {
        return new TimesView(hhmm(t.start()), hhmm(t.end()), hhmm(t.opens()), hhmm(t.onTimeUntil()),
                hhmm(t.lateUntil()), hhmm(t.closes()));
    }

    /** key แบบ snake_case ตามคอลัมน์ (เหมือน audit อื่น) */
    private static Map<String, Object> snapshot(Times t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("start_time", hhmm(t.start()));
        m.put("end_time", hhmm(t.end()));
        m.put("check_in_opens", hhmm(t.opens()));
        m.put("on_time_until", hhmm(t.onTimeUntil()));
        m.put("late_until", hhmm(t.lateUntil()));
        m.put("check_in_closes", hhmm(t.closes()));
        return m;
    }

    private static Map<String, Object> rowSnapshot(String status, String source, LocalDateTime checkIn) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", status);
        m.put("source", source);
        m.put("check_in_time", checkIn == null ? null : checkIn.toString());
        return m;
    }

    private static int mins(LocalTime t) {
        return t.toSecondOfDay() / 60;
    }

    private static LocalTime time(int minuteOfDay) {
        return LocalTime.of(minuteOfDay / 60, minuteOfDay % 60);
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
