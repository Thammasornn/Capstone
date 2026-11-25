package com.faculty.attendance.attendance;

import com.faculty.attendance.attendance.AttendanceImportRepository.CurrentRecord;
import com.faculty.attendance.attendance.AttendanceImportRepository.EnrolledStudent;
import com.faculty.attendance.attendance.AttendanceImportRepository.ImportSession;
import com.faculty.attendance.attendance.AttendanceImportRepository.NewImport;
import com.faculty.attendance.attendance.AttendanceImportRepository.NewRow;
import com.faculty.attendance.attendance.ImportDtos.*;
import com.faculty.attendance.attendance.ImportFileReader.DataRow;
import com.faculty.attendance.attendance.ImportFileReader.ParsedFile;
import com.faculty.attendance.audit.AuditLogRepository;
import com.faculty.attendance.auth.AccessService;
import com.faculty.attendance.common.ImportValidationException;
import com.faculty.attendance.common.ImportValidationException.RowError;
import com.faculty.attendance.common.Times;
import com.faculty.attendance.room.RoomDtos.CheckInEvent;
import com.faculty.attendance.room.RoomEventHub;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * นำเข้าผลเช็คชื่อจากไฟล์ (คลาสออนไซต์) · ไม่เดาอะไรเอง: template = คอลัมน์ / ค่าตายตัว, mapped = ผู้ใช้บอกคอลัมน์ /
 * รูปแบบเวลา / ความหมายของค่าสถานะใน mapping · ไฟล์ผิด = 400 พร้อม error ทุกแถว ไม่บันทึกอะไร
 *
 * กติกาการทับผลเดิม (ลำดับปกติ manual > import > camera > system):
 *   ไม่มีแถว → insert · system → replace · import ครั้งก่อน → replace (ไฟล์ล่าสุดชนะ)
 *   camera ผลเหมือนไฟล์ → skip · camera ผลต่าง → conflict (ทับเมื่อ overrideCamera)
 *   manual → conflict เสมอ (ทับเมื่อ overrideManual) · คนที่ไม่อยู่ในไฟล์ → ไม่แตะ
 * override = ผู้ใช้ยืนยันเองว่าไฟล์ถูกกว่าผลของกล้อง / ที่อาจารย์แก้ไว้ (บันทึก flag ไว้ใน attendance_imports)
 */
@Service
public class AttendanceImportService {

    public static final List<String> TEMPLATE_HEADERS = List.of("student_code", "student_name", "status",
            "check_in_time");
    private static final Set<String> MODES = Set.of("template", "mapped");
    private static final Set<String> METHODS = Set.of("template", "teams", "zoom", "google_form", "paper", "other");
    private static final Set<String> STATUSES = Set.of("on_time", "late", "absent");
    private static final Pattern CODE = Pattern.compile("^\\d{11}$");
    private static final Pattern EMAIL = Pattern.compile("^(\\d{11})@[^@\\s]+$", Pattern.CASE_INSENSITIVE);
    private static final Pattern HHMM = Pattern.compile("^\\d{2}:\\d{2}$");
    private static final int REASON_MAX = 300;

    private enum Kind { TIME, DATE, DATETIME }

    private record TimeFormat(DateTimeFormatter formatter, Kind kind) {}

    /** รูปแบบเวลาที่รับใน mapping (ไม่มีรูปแบบอื่น) · เวลาอย่างเดียวใช้วันที่ของคาบ · วันที่อย่างเดียว = ไม่มีเวลาเข้า */
    static final Map<String, TimeFormat> TIME_FORMATS = new LinkedHashMap<>();

    static {
        TIME_FORMATS.put("HH:mm", fmt("HH:mm", Kind.TIME));
        TIME_FORMATS.put("HH:mm:ss", fmt("HH:mm:ss", Kind.TIME));
        TIME_FORMATS.put("d/M/yyyy HH:mm:ss", fmt("d/M/yyyy HH:mm:ss", Kind.DATETIME));
        TIME_FORMATS.put("M/d/yyyy", fmt("M/d/yyyy", Kind.DATE));
        TIME_FORMATS.put("h:mm:ss a", fmt("h:mm:ss a", Kind.TIME));
        TIME_FORMATS.put("yyyy-MM-dd HH:mm:ss", fmt("yyyy-MM-dd HH:mm:ss", Kind.DATETIME));
    }

    private static TimeFormat fmt(String pattern, Kind kind) {
        return new TimeFormat(new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern(pattern)
                .toFormatter(Locale.US), kind);
    }

    private final AttendanceImportRepository repo;
    private final AccessService access;
    private final AuditLogRepository audit;
    private final RoomEventHub hub;
    private final TransactionTemplate tx;
    private final JsonMapper json;

    public AttendanceImportService(AttendanceImportRepository repo, AccessService access, AuditLogRepository audit,
                                   RoomEventHub hub, TransactionTemplate tx, JsonMapper json) {
        this.repo = repo;
        this.access = access;
        this.audit = audit;
        this.hub = hub;
        this.tx = tx;
        this.json = json;
    }

    // ---------- template ----------

    public record TemplateFile(String fileName, String contentType, byte[] bytes) {}

    public TemplateFile template(UUID sessionId, String format) {
        access.requireSession(sessionId);
        ImportSession s = session(sessionId);
        String f = format == null ? "xlsx" : format.trim().toLowerCase(Locale.ROOT);
        if (!f.equals("xlsx") && !f.equals("csv")) throw bad("format ต้องเป็น xlsx หรือ csv");
        var students = repo.activeStudentsByCode(s.sectionId()).values();
        String name = "attendance_%s-%s_%s.%s".formatted(s.courseCode(), s.sectionCode(), s.sessionDate(), f);
        return f.equals("csv")
                ? new TemplateFile(name, "text/csv; charset=UTF-8", ImportTemplates.csv(TEMPLATE_HEADERS, students))
                : new TemplateFile(name, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                        ImportTemplates.xlsx(TEMPLATE_HEADERS, students));
    }

    // ---------- inspect ----------

    public InspectResponse inspect(UUID sessionId, MultipartFile file) {
        access.requireSession(sessionId);
        requireImportable(session(sessionId), Times.now());
        ParsedFile p = ImportFileReader.read(file);
        List<List<String>> sample = new ArrayList<>();
        for (int i = 0; i < Math.min(5, p.rows().size()); i++) sample.add(p.rows().get(i).cells());
        Map<String, List<String>> distinct = new LinkedHashMap<>();
        for (int c = 0; c < p.headers().size(); c++) {
            Set<String> vals = new LinkedHashSet<>();
            for (DataRow r : p.rows()) {
                String v = r.cells().get(c);
                if (!v.isEmpty()) vals.add(v);
                if (vals.size() > 20) break;
            }
            if (!vals.isEmpty() && vals.size() <= 20) distinct.put(p.headers().get(c), List.copyOf(vals));
        }
        return new InspectResponse(p.headers(), sample, distinct, p.rows().size());
    }

    // ---------- import ----------

    public record ImportParams(String mode, String method, String reason, boolean dryRun, boolean overrideCamera,
                               boolean overrideManual, String mappingJson) {}

    public ImportResponse importFile(UUID sessionId, MultipartFile file, ImportParams in, String ip) {
        access.requireSession(sessionId);
        ImportSession s0 = session(sessionId);
        requireImportable(s0, Times.now());

        String mode = lower(in.mode());
        if (!MODES.contains(mode)) throw bad("mode ต้องเป็น template หรือ mapped");
        String method = lower(in.method());
        if (method.isEmpty() && mode.equals("template")) method = "template";
        if (!METHODS.contains(method)) {
            throw bad("method ต้องเป็น template, teams, zoom, google_form, paper หรือ other");
        }
        if (mode.equals("template") != method.equals("template")) {
            throw bad("mode = template ต้องใช้ method = template (และ method = template ใช้ได้กับ mode = template เท่านั้น)");
        }
        String reason = in.reason() == null ? "" : in.reason().trim();
        if (reason.isEmpty()) throw bad("กรุณาระบุเหตุผลการนำเข้า");
        if (reason.length() > REASON_MAX) throw bad("เหตุผลยาวได้ไม่เกิน " + REASON_MAX + " ตัวอักษร");
        Mapping mapping = null;
        if (mode.equals("mapped")) {
            if (in.mappingJson() == null || in.mappingJson().isBlank()) throw bad("mode = mapped ต้องส่ง mapping");
            try {
                mapping = json.readValue(in.mappingJson(), Mapping.class);
            } catch (JacksonException e) {
                throw bad("mapping ต้องเป็น JSON ที่ถูกต้อง");
            }
        }

        ParsedFile file0 = ImportFileReader.read(file);
        List<Entry> entries = mode.equals("template") ? templateEntries(file0, s0) : mappedEntries(file0, mapping, s0);
        String mappingJson = mapping == null ? null : json.writeValueAsString(mapping);
        String fileName = file0.fileName().isEmpty() ? "import." + (mode.equals("template") ? "xlsx" : "csv")
                : file0.fileName();

        var students = repo.activeStudentsByCode(s0.sectionId());
        Set<String> codesInFile = new HashSet<>();
        for (Entry e : entries) codesInFile.add(e.code());
        Set<String> existing = repo.existingStudentCodes(codesInFile);
        DuplicateOf dup = repo.findImportBySha(sessionId, file0.sha256())
                .map(p -> new DuplicateOf(p.importId(), Times.local(p.importedAt()), p.importedByName(), p.fileName()))
                .orElse(null);

        if (in.dryRun()) {
            Plan plan = plan(s0, entries, students, existing, repo.currentRecords(sessionId, false), in);
            return response(true, mode, null, dup, s0, plan);
        }

        UUID actor = access.user().userId();
        final String m = method;
        record Done(Plan plan, UUID importId, ImportSession s) {}
        Done done = tx.execute(t -> {
            ImportSession s = repo.lockSession(sessionId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "ไม่พบคาบเรียน"));
            requireImportable(s, Times.now());
            Plan plan = plan(s, entries, students, existing, repo.currentRecords(sessionId, true), in);
            ImportSummary c = plan.summary();
            UUID importId = repo.insertImport(new NewImport(sessionId, mode, m, fileName, file0.sha256(), mappingJson,
                    reason, in.overrideCamera(), in.overrideManual(), c.totalRows(), c.insert(), c.replace(),
                    c.skip(), c.conflict(), c.unmatched(), actor));
            List<NewRow> rows = new ArrayList<>();
            for (Planned r : plan.rows()) {
                rows.add(new NewRow(r.entry().rowNo(), r.entry().rawIdentifier(), r.entry().rawJoinTime(),
                        r.entry().rawStatus(), r.studentId(), r.toStatus(), Times.offset(r.checkInTime()),
                        r.action(), r.unmatchedReason(), r.apply()));
                if (r.apply()) {
                    repo.upsertImported(sessionId, r.studentId(), r.toStatus(), Times.offset(r.checkInTime()),
                            importId);
                }
            }
            repo.insertRows(importId, rows);
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("session_id", sessionId.toString());
            after.put("mode", mode);
            after.put("method", m);
            after.put("file_name", fileName);
            after.put("summary", c);
            audit.log(actor, "attendance.import", "attendance_import", importId, null,
                    json.writeValueAsString(after), ip);
            return new Done(plan, importId, s);
        });

        ImportSession s = done.s();
        LocalDateTime now = Times.now();
        boolean running = !"completed".equals(s.sessionStatus()) && !"cancelled".equals(s.sessionStatus())
                && !s.sessionDate().atTime(s.startTime()).isAfter(now)
                && s.sessionDate().atTime(s.endTime()).isAfter(now);
        if (running) {
            for (Planned r : done.plan().rows()) {
                if (!r.apply()) continue;
                hub.publish(s.roomId(), "check_in", new CheckInEvent(sessionId, r.studentId(), r.studentCode(),
                        r.checkInTime(), r.toStatus(), "import"));
            }
        }
        return response(false, mode, done.importId(), dup, s, done.plan());
    }

    // ---------- history ----------

    public List<HistoryItem> history(UUID sessionId) {
        access.requireSession(sessionId);
        session(sessionId);
        return repo.history(sessionId).stream().map(AttendanceImportService::historyItem).toList();
    }

    public ImportDetail detail(UUID sessionId, UUID importId) {
        access.requireSession(sessionId);
        session(sessionId);
        UUID owner = repo.sessionOfImport(importId).orElse(null);
        if (!sessionId.equals(owner)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "ไม่พบการนำเข้านี้");
        var head = repo.history(sessionId).stream().filter(h -> h.importId().equals(importId)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "ไม่พบการนำเข้านี้"));
        List<StoredRow> rows = repo.rows(importId).stream().map(r -> new StoredRow(r.rowNo(), r.rawIdentifier(),
                r.rawJoinTime(), r.rawStatus(), r.studentId(), r.studentCode(), r.studentName(), r.status(),
                Times.local(r.checkInTime()), r.action(), r.unmatchedReason(), r.applied())).toList();
        return new ImportDetail(historyItem(head), head.mappingJson() == null ? null : json.readTree(head.mappingJson()),
                rows);
    }

    private static HistoryItem historyItem(AttendanceImportRepository.ImportSummary h) {
        return new HistoryItem(h.importId(), Times.local(h.importedAt()), h.importedBy(), h.importedByName(),
                h.mode(), h.method(), h.fileName(), h.reason(), h.overrideCamera(), h.overrideManual(),
                h.totalRows(), h.insertedCount(), h.replacedCount(), h.skippedCount(), h.conflictCount(),
                h.unmatchedCount());
    }

    // ---------- parse rows ----------

    /**
     * 1 แถวที่อ่านได้ · code = รหัส 11 หลัก · status = สถานะจากไฟล์ (null = ไม่มี) · time = เวลาเข้าจากไฟล์ (null = ไม่มี) ·
     * fallback = defaultStatus (mapped) เมื่อไม่มีทั้งสถานะและเวลา
     */
    record Entry(int rowNo, String rawIdentifier, String rawJoinTime, String rawStatus, String code, String status,
                 LocalDateTime time) {}

    private List<Entry> templateEntries(ParsedFile p, ImportSession s) {
        List<RowError> errors = new ArrayList<>();
        if (!p.headers().equals(TEMPLATE_HEADERS)) {
            throw new ImportValidationException("หัวตารางไม่ตรงกับ template", List.of(new RowError(1, null,
                    String.join(",", p.headers()), "หัวตารางต้องเป็น " + String.join(", ", TEMPLATE_HEADERS)
                    + " ตามลำดับ (ดาวน์โหลด template ใหม่)")));
        }
        List<Entry> out = new ArrayList<>();
        for (DataRow r : p.rows()) {
            String code = r.cells().get(0), status = r.cells().get(2), time = r.cells().get(3);
            if (status.isEmpty()) {
                if (!time.isEmpty()) {
                    errors.add(new RowError(r.rowNo(), "status", "", "กรอก check_in_time แล้วต้องระบุ status ด้วย"));
                }
                continue;      // status ว่าง = ไม่เปลี่ยน (ไม่นับใน total_rows)
            }
            int before = errors.size();
            if (!CODE.matcher(code).matches()) {
                errors.add(new RowError(r.rowNo(), "student_code", code, "student_code ต้องเป็นตัวเลข 11 หลัก"));
            }
            if (!STATUSES.contains(status)) {
                errors.add(new RowError(r.rowNo(), "status", status, "ค่า status ต้องเป็น on_time, late หรือ absent"));
            }
            LocalDateTime t = null;
            if (!time.isEmpty()) {
                if ("absent".equals(status)) {
                    errors.add(new RowError(r.rowNo(), "check_in_time", time, "absent ต้องไม่มี check_in_time"));
                } else if (!HHMM.matcher(time).matches()) {
                    errors.add(new RowError(r.rowNo(), "check_in_time", time, "check_in_time ต้องเป็นรูปแบบ HH:mm เช่น 13:05"));
                } else {
                    try {
                        t = s.sessionDate().atTime(LocalTime.parse(time));
                    } catch (DateTimeParseException e) {
                        errors.add(new RowError(r.rowNo(), "check_in_time", time, "check_in_time ไม่ใช่เวลาที่ถูกต้อง"));
                    }
                }
            }
            if (errors.size() == before) out.add(new Entry(r.rowNo(), code, time, status, code, status, t));
        }
        failIfAny(errors);
        if (out.isEmpty()) throw ImportValidationException.file("ไม่มีแถวที่ระบุ status (status ว่าง = ไม่เปลี่ยน)");
        return out;
    }

    private List<Entry> mappedEntries(ParsedFile p, Mapping m, ImportSession s) {
        List<RowError> errors = new ArrayList<>();
        List<String> h = p.headers();
        int idCol = column(h, m.identifierColumn(), "identifierColumn", true, errors);
        String idType = lower(m.identifierType());
        if (!idType.equals("email") && !idType.equals("student_code")) {
            errors.add(new RowError(null, "identifierType", m.identifierType(), "identifierType ต้องเป็น email หรือ student_code"));
        }
        int timeCol = column(h, m.joinTimeColumn(), "joinTimeColumn", false, errors);
        TimeFormat tf = null;
        if (timeCol >= 0) {
            tf = m.timeFormat() == null ? null : TIME_FORMATS.get(m.timeFormat().trim());
            if (tf == null) {
                errors.add(new RowError(null, "timeFormat", m.timeFormat(),
                        "timeFormat ต้องเป็นหนึ่งใน " + String.join(" | ", TIME_FORMATS.keySet())));
            }
        }
        int statusCol = column(h, m.statusColumn(), "statusColumn", false, errors);
        Map<String, String> values = new HashMap<>();
        if (statusCol >= 0) {
            if (m.statusValues() == null || m.statusValues().isEmpty()) {
                errors.add(new RowError(null, "statusValues", null, "เลือก statusColumn แล้วต้องกำหนด statusValues"));
            } else {
                m.statusValues().forEach((k, v) -> {
                    String to = lower(v);
                    if (!STATUSES.contains(to)) {
                        errors.add(new RowError(null, "statusValues", k + " → " + v,
                                "statusValues ต้องแปลงเป็น on_time, late หรือ absent"));
                    }
                    values.put(k == null ? "" : k.trim(), to);
                });
            }
        }
        String def = m.defaultStatus() == null || m.defaultStatus().isBlank() ? null : lower(m.defaultStatus());
        if (def != null && !def.equals("on_time") && !def.equals("late")) {
            errors.add(new RowError(null, "defaultStatus", m.defaultStatus(), "defaultStatus ต้องเป็น on_time หรือ late"));
        }
        if (timeCol < 0 && statusCol < 0 && def == null) {
            errors.add(new RowError(null, "defaultStatus", null,
                    "ไม่ได้เลือกคอลัมน์เวลาหรือสถานะ ต้องกำหนด defaultStatus (on_time หรือ late)"));
        }
        if (!errors.isEmpty()) throw new ImportValidationException("การจับคู่คอลัมน์ไม่ถูกต้อง", errors);

        List<Entry> out = new ArrayList<>();
        for (DataRow r : p.rows()) {
            int before = errors.size();
            String rawId = r.cells().get(idCol);
            String code = null;
            if (rawId.isEmpty()) {
                errors.add(new RowError(r.rowNo(), h.get(idCol), "", "ไม่มีค่าตัวระบุนักศึกษา"));
            } else if (idType.equals("email")) {
                Matcher mm = EMAIL.matcher(rawId);
                if (mm.matches()) code = mm.group(1);
                else errors.add(new RowError(r.rowNo(), h.get(idCol), rawId, "อีเมลต้องเป็น <รหัสนักศึกษา 11 หลัก>@..."));
            } else if (CODE.matcher(rawId).matches()) {
                code = rawId;
            } else {
                errors.add(new RowError(r.rowNo(), h.get(idCol), rawId, "รหัสนักศึกษาต้องเป็นตัวเลข 11 หลัก"));
            }

            String rawTime = timeCol >= 0 ? r.cells().get(timeCol) : null;
            LocalDateTime time = null;
            if (rawTime != null && !rawTime.isEmpty()) {
                try {
                    time = switch (tf.kind()) {
                        case TIME -> s.sessionDate().atTime(LocalTime.parse(rawTime, tf.formatter()));
                        case DATETIME -> LocalDateTime.parse(rawTime, tf.formatter());
                        case DATE -> {
                            LocalDate.parse(rawTime, tf.formatter());   // ตรวจรูปแบบ · ไม่มีเวลาเข้า
                            yield null;
                        }
                    };
                } catch (DateTimeParseException e) {
                    errors.add(new RowError(r.rowNo(), h.get(timeCol), rawTime,
                            "เวลาไม่ตรงรูปแบบ " + m.timeFormat().trim()));
                }
            }

            String rawStatus = statusCol >= 0 ? r.cells().get(statusCol) : null;
            String status = null;
            if (rawStatus != null && !rawStatus.isEmpty()) {
                status = values.get(rawStatus);
                if (status == null) {
                    errors.add(new RowError(r.rowNo(), h.get(statusCol), rawStatus,
                            "ค่า \"" + rawStatus + "\" ไม่อยู่ใน statusValues"));
                }
            }
            if (status == null && time == null && def == null && errors.size() == before) {
                errors.add(new RowError(r.rowNo(), null, null, "แถวนี้ไม่มีทั้งเวลาและสถานะ ต้องกำหนด defaultStatus"));
            }
            if (status == null && time == null) status = def;
            if (errors.size() == before) out.add(new Entry(r.rowNo(), rawId, rawTime, rawStatus, code, status, time));
        }
        failIfAny(errors);
        return out;
    }

    private static int column(List<String> headers, String name, String field, boolean required,
                              List<RowError> errors) {
        if (name == null || name.isBlank()) {
            if (required) errors.add(new RowError(null, field, null, "กรุณาเลือก " + field));
            return -1;
        }
        int i = headers.indexOf(name.trim());
        if (i < 0) errors.add(new RowError(null, field, name, "ไม่พบคอลัมน์ \"" + name + "\" ในไฟล์"));
        return i;
    }

    private static void failIfAny(List<RowError> errors) {
        if (errors.isEmpty()) return;
        long rows = errors.stream().map(RowError::rowNo).filter(java.util.Objects::nonNull).distinct().count();
        throw new ImportValidationException(rows == 0 ? "ไฟล์มีข้อผิดพลาด" : "ไฟล์มีข้อผิดพลาด " + rows + " แถว", errors);
    }

    // ---------- plan ----------

    /** ผลคำนวณ 1 แถว · apply = จะเขียน / เขียนลง attendance_records (insert / replace / conflict ที่ override) */
    record Planned(Entry entry, UUID studentId, String studentCode, String studentName, String fromSource,
                   String fromStatus, String toStatus, LocalDateTime checkInTime, String action,
                   String unmatchedReason, boolean apply) {}

    record Plan(List<Planned> rows, ImportSummary summary) {}

    static Plan plan(ImportSession s, List<Entry> entries, Map<String, EnrolledStudent> students,
                     Set<String> existingCodes, Map<UUID, CurrentRecord> current, ImportParams in) {
        List<Planned> rows = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int ins = 0, rep = 0, skip = 0, conf = 0, unm = 0, apply = 0;
        LocalDateTime start = s.sessionDate().atTime(s.startTime());
        LocalDateTime end = s.sessionDate().atTime(s.endTime());
        for (Entry e : entries) {
            if (!seen.add(e.code())) {
                rows.add(unmatched(e, "duplicate"));
                unm++;
                continue;
            }
            EnrolledStudent st = students.get(e.code());
            if (st == null) {
                rows.add(unmatched(e, existingCodes.contains(e.code()) ? "not_enrolled" : "not_found"));
                unm++;
                continue;
            }
            String status = e.status() != null ? e.status()
                    : !e.time().isAfter(s.sessionDate().atTime(s.onTimeUntil())) ? "on_time" : "late";
            LocalDateTime checkIn = null;
            if (!"absent".equals(status)) {
                LocalDateTime t = e.time();
                checkIn = t != null && !t.isBefore(start.minusHours(1)) && !t.isAfter(end) ? t.withNano(0) : start;
            }
            CurrentRecord cur = current.get(st.studentId());
            String fromSource = cur == null ? null : cur.source();
            String fromStatus = cur == null ? null : cur.status();
            String action;
            boolean doApply;
            if (cur == null) {
                action = "insert";
                doApply = true;
            } else {
                switch (cur.source()) {
                    case "system", "import" -> { action = "replace"; doApply = true; }
                    case "camera" -> {
                        if (cur.status().equals(status)) { action = "skip"; doApply = false; }
                        else { action = "conflict"; doApply = in.overrideCamera(); }
                    }
                    default -> { action = "conflict"; doApply = in.overrideManual(); }   // manual
                }
            }
            switch (action) {
                case "insert" -> ins++;
                case "replace" -> rep++;
                case "skip" -> skip++;
                default -> conf++;
            }
            if (doApply) apply++;
            rows.add(new Planned(e, st.studentId(), st.studentCode(), st.fullName(), fromSource, fromStatus, status,
                    checkIn, action, null, doApply));
        }
        return new Plan(rows, new ImportSummary(entries.size(), ins, rep, skip, conf, unm, apply));
    }

    private static Planned unmatched(Entry e, String reason) {
        return new Planned(e, null, null, null, null, null, null, null, "unmatched", reason, false);
    }

    private static ImportResponse response(boolean dryRun, String mode, UUID importId, DuplicateOf dup,
                                           ImportSession s, Plan plan) {
        List<ImportRowResult> rows = new ArrayList<>();
        List<Unmatched> unmatched = new ArrayList<>();
        for (Planned r : plan.rows()) {
            if ("unmatched".equals(r.action())) {
                unmatched.add(new Unmatched(r.entry().rowNo(), r.entry().rawIdentifier(), r.unmatchedReason()));
            } else {
                rows.add(new ImportRowResult(r.entry().rowNo(), r.studentId(), r.studentCode(), r.studentName(),
                        r.fromSource(), r.fromStatus(), r.toStatus(), r.checkInTime(), r.action(),
                        !dryRun && r.apply()));
            }
        }
        ImportSummary c = plan.summary();
        ImportSummary summary = new ImportSummary(c.totalRows(), c.insert(), c.replace(), c.skip(), c.conflict(),
                c.unmatched(), dryRun ? 0 : c.applied());
        return new ImportResponse(dryRun, mode, importId, dup, new SessionWindow(hhmm(s.startTime()),
                hhmm(s.onTimeUntil()), hhmm(s.lateUntil()), hhmm(s.endTime())), rows, unmatched, summary);
    }

    // ---------- helpers ----------

    private ImportSession session(UUID sessionId) {
        return repo.findSession(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "ไม่พบคาบเรียน"));
    }

    /** คาบที่ยกเลิก / ยังไม่ถึงเวลาเริ่ม นำเข้าไม่ได้ (job อัปเดต session_status ช้าได้ ≤ 1 นาที จึงดูเวลาจริงด้วย) */
    private static void requireImportable(ImportSession s, LocalDateTime now) {
        if ("cancelled".equals(s.sessionStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "คาบนี้ถูกยกเลิกแล้ว นำเข้าผลเช็คชื่อไม่ได้");
        }
        if ("scheduled".equals(s.sessionStatus()) && s.sessionDate().atTime(s.startTime()).isAfter(now)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "คาบนี้ยังไม่เริ่ม นำเข้าผลเช็คชื่อไม่ได้");
        }
    }

    private static String lower(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }

    private static String hhmm(LocalTime t) {
        return "%02d:%02d".formatted(t.getHour(), t.getMinute());
    }

    private static ResponseStatusException bad(String msg) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
    }
}
