package com.faculty.attendance.enrollment;

import com.faculty.attendance.attendance.ImportFileReader;
import com.faculty.attendance.attendance.ImportFileReader.DataRow;
import com.faculty.attendance.attendance.ImportFileReader.ParsedFile;
import com.faculty.attendance.audit.AuditLogRepository;
import com.faculty.attendance.auth.AccessService;
import com.faculty.attendance.common.ImportValidationException;
import com.faculty.attendance.common.ImportValidationException.RowError;
import com.faculty.attendance.common.Times;
import com.faculty.attendance.enrollment.EnrollmentRepository.SectionInfo;
import com.faculty.attendance.enrollment.EnrollmentRepository.StudentRef;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

/**
 * นักศึกษาในกลุ่มเรียน (enrollments) · admin / อาจารย์ผู้สอนกลุ่มนั้น · เพิ่มได้ 2 วิธี: ป้อนเอง (POST) และนำเข้าไฟล์
 * (CSV / TXT / XLSX · template + dryRun) · ถอน = soft (dropped · ผลเช็คชื่อเก็บไว้)
 *
 * action ต่อคน: insert (เพิ่มใหม่) · reactivate (เคยถอน → active) · create (ไม่มีบัญชี → สร้างบัญชีนักศึกษา + เพิ่ม) ·
 * skip (อยู่ในกลุ่มแล้ว) · unmatched (not_found / duplicate / account_inactive / email_taken)
 * สร้างบัญชีได้เฉพาะเมื่อตั้ง app.enrollment.new-student-password (local / uat · ห้ามตั้งใน prod) และส่งชื่อ-นามสกุลมา
 * enrolled_at = now() → ระบบลงขาดอัตโนมัติเฉพาะคาบที่เริ่มหลังเพิ่มเข้ากลุ่ม (คาบก่อนหน้าไม่นับ)
 */
@Service
public class EnrollmentService {

    public static final List<String> TEMPLATE_HEADERS = List.of("student_code", "title", "first_name", "last_name",
            "email");
    private static final Pattern CODE = Pattern.compile("^\\d{11}$");
    private static final Pattern EMAIL = Pattern.compile("^(\\d{11})@[^@\\s]+\\.[^@\\s]+$");
    private static final String DEFAULT_DOMAIN = "@st.sit.kmutt.ac.th";
    private static final int MAX_ITEMS = 500;
    private static final int REASON_MAX = 500;

    // ---------- DTOs ----------

    public record StudentInput(String studentCode, String email, String title, String firstName, String lastName) {}

    public record AddRequest(List<StudentInput> students, String reason, Boolean dryRun) {}

    public record DropRequest(String reason) {}

    public record EnrolledStudent(UUID studentId, String studentCode, String title, String firstName, String lastName,
                                  String fullName, String email, boolean accountActive, String status,
                                  LocalDateTime enrolledAt, LocalDateTime droppedAt, boolean faceRegistered,
                                  String consentStatus) {}

    public record EnrollmentList(UUID sectionId, String courseCode, String sectionCode, boolean canCreateAccounts,
                                 int activeCount, List<EnrolledStudent> items) {}

    /** rowNo = ลำดับในคำขอ (เริ่ม 1) หรือเลขแถวในไฟล์ (หัวตาราง = 1) */
    public record RowResult(int rowNo, String identifier, UUID studentId, String studentCode, String studentName,
                            String email, String action, String reason, boolean applied) {}

    public record Summary(int total, int insert, int reactivate, int create, int skip, int unmatched, int applied) {}

    public record AddResponse(boolean dryRun, UUID sectionId, boolean canCreateAccounts, List<RowResult> rows,
                              Summary summary) {}

    public record DropResponse(UUID studentId, String studentCode, String status, LocalDateTime droppedAt,
                               int keptAttendanceCount) {}

    public record TemplateFile(String fileName, String contentType, byte[] bytes) {}

    // ---------- deps ----------

    private final EnrollmentRepository repo;
    private final AccessService access;
    private final AuditLogRepository audit;
    private final TransactionTemplate tx;
    private final JsonMapper json;
    private final PasswordEncoder encoder;
    private final String newStudentPassword;

    public EnrollmentService(EnrollmentRepository repo, AccessService access, AuditLogRepository audit,
                             TransactionTemplate tx, JsonMapper json, PasswordEncoder encoder,
                             @Value("${app.enrollment.new-student-password:}") String newStudentPassword) {
        this.repo = repo;
        this.access = access;
        this.audit = audit;
        this.tx = tx;
        this.json = json;
        this.encoder = encoder;
        this.newStudentPassword = newStudentPassword == null ? "" : newStudentPassword;
    }

    private boolean canCreate() {
        return !newStudentPassword.isBlank();
    }

    // ---------- list ----------

    public EnrollmentList list(UUID sectionId, String status) {
        access.requireSectionStaff(sectionId);
        SectionInfo s = section(sectionId);
        String f = status == null || status.isBlank() ? null : status.trim().toLowerCase(Locale.ROOT);
        if (f != null && !f.equals("active") && !f.equals("dropped")) throw bad("status ต้องเป็น active หรือ dropped");
        var rows = repo.list(sectionId);
        int active = (int) rows.stream().filter(r -> "active".equals(r.status())).count();
        List<EnrolledStudent> items = rows.stream().filter(r -> f == null || f.equals(r.status()))
                .map(r -> new EnrolledStudent(r.studentId(), r.studentCode(), r.title(), r.firstName(), r.lastName(),
                        r.firstName() + " " + r.lastName(), r.email(), r.accountActive(), r.status(),
                        Times.local(r.enrolledAt()), Times.local(r.droppedAt()), r.faceRegistered(), r.consentStatus()))
                .toList();
        return new EnrollmentList(sectionId, s.courseCode(), s.sectionCode(), canCreate(), active, items);
    }

    // ---------- add (manual) ----------

    public AddResponse add(UUID sectionId, AddRequest body, String ip) {
        access.requireSectionStaff(sectionId);
        section(sectionId);
        if (body == null || body.students() == null || body.students().isEmpty()) throw bad("กรุณาระบุนักศึกษาอย่างน้อย 1 คน");
        if (body.students().size() > MAX_ITEMS) throw bad("เพิ่มได้ครั้งละไม่เกิน " + MAX_ITEMS + " คน");
        String reason = reason(body.reason());
        List<Candidate> cands = new ArrayList<>();
        List<RowError> errors = new ArrayList<>();
        for (int i = 0; i < body.students().size(); i++) {
            StudentInput in = body.students().get(i);
            if (in == null) {
                errors.add(new RowError(i + 1, null, null, "ข้อมูลนักศึกษาไม่ถูกต้อง"));
                continue;
            }
            candidate(i + 1, in.studentCode(), in.email(), in.title(), in.firstName(), in.lastName(), errors)
                    .ifPresent(cands::add);
        }
        if (!errors.isEmpty()) throw new ImportValidationException("ข้อมูลไม่ถูกต้อง " + rowsOf(errors) + " รายการ", errors);
        return run(sectionId, cands, body.dryRun() != null && body.dryRun(), reason, "manual", null, ip);
    }

    // ---------- import ----------

    public TemplateFile template(UUID sectionId, String format) {
        access.requireSectionStaff(sectionId);
        SectionInfo s = section(sectionId);
        String f = format == null ? "xlsx" : format.trim().toLowerCase(Locale.ROOT);
        if (!f.equals("xlsx") && !f.equals("csv")) throw bad("format ต้องเป็น xlsx หรือ csv");
        String name = "students_%s-%s.%s".formatted(s.courseCode(), s.sectionCode(), f);
        return f.equals("csv")
                ? new TemplateFile(name, "text/csv; charset=UTF-8",
                        ("﻿" + String.join(",", TEMPLATE_HEADERS) + "\r\n").getBytes(StandardCharsets.UTF_8))
                : new TemplateFile(name, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", xlsx());
    }

    public AddResponse importFile(UUID sectionId, MultipartFile file, boolean dryRun, String reason, String ip) {
        access.requireSectionStaff(sectionId);
        section(sectionId);
        String r = reason(reason);
        ParsedFile p = ImportFileReader.read(file, true);
        Map<String, Integer> col = new LinkedHashMap<>();
        List<RowError> errors = new ArrayList<>();
        for (int i = 0; i < p.headers().size(); i++) {
            String h = p.headers().get(i).toLowerCase(Locale.ROOT);
            if (!TEMPLATE_HEADERS.contains(h)) {
                errors.add(new RowError(1, p.headers().get(i), p.headers().get(i),
                        "ไม่รู้จักคอลัมน์ \"" + p.headers().get(i) + "\" (ใช้ได้: " + String.join(", ", TEMPLATE_HEADERS) + ")"));
            } else {
                col.put(h, i);
            }
        }
        if (!col.containsKey("student_code") && !col.containsKey("email")) {
            errors.add(new RowError(1, null, null, "ต้องมีคอลัมน์ student_code หรือ email"));
        }
        if (!errors.isEmpty()) throw new ImportValidationException("หัวตารางไม่ถูกต้อง", errors);
        List<Candidate> cands = new ArrayList<>();
        for (DataRow row : p.rows()) {
            candidate(row.rowNo(), cell(row, col, "student_code"), cell(row, col, "email"), cell(row, col, "title"),
                    cell(row, col, "first_name"), cell(row, col, "last_name"), errors).ifPresent(cands::add);
        }
        if (!errors.isEmpty()) {
            throw new ImportValidationException("ไฟล์มีข้อผิดพลาด " + rowsOf(errors) + " แถว", errors);
        }
        return run(sectionId, cands, dryRun, r, "import", p, ip);
    }

    // ---------- drop ----------

    public DropResponse drop(UUID sectionId, UUID studentId, DropRequest body, String ip) {
        access.requireSectionStaff(sectionId);
        section(sectionId);
        String reason = reason(body == null ? null : body.reason());
        return tx.execute(t -> {
            repo.section(sectionId, true);
            String st = repo.enrollmentStatus(sectionId, studentId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "นักศึกษาไม่ได้อยู่ในกลุ่มเรียนนี้"));
            if ("dropped".equals(st)) throw new ResponseStatusException(HttpStatus.CONFLICT, "นักศึกษาถูกถอนจากกลุ่มเรียนนี้แล้ว");
            var droppedAt = repo.drop(sectionId, studentId).orElseThrow();
            StudentRef ref = repo.list(sectionId).stream().filter(x -> x.studentId().equals(studentId)).findFirst()
                    .map(x -> new StudentRef(x.studentId(), x.studentCode(), x.email(), null, true, "dropped"))
                    .orElseThrow();
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("section_id", sectionId.toString());
            after.put("status", "dropped");
            if (reason != null) after.put("reason", reason);
            audit.log(access.user().userId(), "enrollment.drop", "enrollment", studentId,
                    "{\"status\": \"active\"}", toJson(after), ip);
            return new DropResponse(studentId, ref.studentCode(), "dropped", Times.local(droppedAt),
                    repo.attendanceCount(sectionId, studentId));
        });
    }

    // ---------- core ----------

    /** 1 คนที่จะเพิ่ม · code = รหัส 11 หลัก (จาก student_code หรือ email) */
    private record Candidate(int rowNo, String identifier, String code, String email, String title, String firstName,
                             String lastName) {}

    private record Planned(Candidate c, StudentRef ref, String action, String reason, String email) {}

    private Optional<Candidate> candidate(int rowNo, String rawCode, String rawEmail, String title, String first,
                                          String last, List<RowError> errors) {
        String code = trim(rawCode), email = trim(rawEmail);
        String t = trim(title), f = trim(first), l = trim(last);
        if (email != null) email = email.toLowerCase(Locale.ROOT);
        int before = errors.size();
        if (code == null && email == null) {
            errors.add(new RowError(rowNo, "student_code", null, "ต้องระบุ student_code หรือ email"));
        }
        if (code != null && !CODE.matcher(code).matches()) {
            errors.add(new RowError(rowNo, "student_code", code, "student_code ต้องเป็นตัวเลข 11 หลัก"));
        }
        String emailCode = null;
        if (email != null) {
            Matcher m = EMAIL.matcher(email);
            if (!m.matches()) {
                errors.add(new RowError(rowNo, "email", email, "email ต้องเป็น <รหัสนักศึกษา 11 หลัก>@..."));
            } else {
                emailCode = m.group(1);
                if (code != null && !code.equals(emailCode)) {
                    errors.add(new RowError(rowNo, "email", email, "รหัสใน email ไม่ตรงกับ student_code"));
                }
            }
        }
        if ((f == null) != (l == null)) {
            errors.add(new RowError(rowNo, f == null ? "first_name" : "last_name", null, "ต้องระบุทั้งชื่อและนามสกุล"));
        }
        if (f != null && f.length() > 100) errors.add(new RowError(rowNo, "first_name", f, "ชื่อยาวได้ไม่เกิน 100 ตัวอักษร"));
        if (l != null && l.length() > 100) errors.add(new RowError(rowNo, "last_name", l, "นามสกุลยาวได้ไม่เกิน 100 ตัวอักษร"));
        if (t != null && t.length() > 20) errors.add(new RowError(rowNo, "title", t, "คำนำหน้ายาวได้ไม่เกิน 20 ตัวอักษร"));
        if (errors.size() != before) return Optional.empty();
        String c = code != null ? code : emailCode;
        return Optional.of(new Candidate(rowNo, code != null ? code : email, c, email, t, f, l));
    }

    private List<Planned> plan(UUID sectionId, List<Candidate> cands) {
        Set<String> codes = new HashSet<>();
        Set<String> emails = new HashSet<>();
        for (Candidate c : cands) {
            codes.add(c.code());
            emails.add(c.email() != null ? c.email() : c.code() + DEFAULT_DOMAIN);
        }
        Map<String, StudentRef> refs = repo.studentsByCode(sectionId, codes);
        Map<String, String> taken = repo.rolesByEmail(emails);
        Set<String> seen = new HashSet<>();
        Set<String> newEmails = new HashSet<>();
        List<Planned> out = new ArrayList<>();
        for (Candidate c : cands) {
            if (!seen.add(c.code())) {
                out.add(new Planned(c, null, "unmatched", "duplicate", null));
                continue;
            }
            StudentRef ref = refs.get(c.code());
            if (ref != null) {
                if (!ref.accountActive()) out.add(new Planned(c, ref, "unmatched", "account_inactive", ref.email()));
                else if ("active".equals(ref.enrollmentStatus())) out.add(new Planned(c, ref, "skip", "already_enrolled", ref.email()));
                else if ("dropped".equals(ref.enrollmentStatus())) out.add(new Planned(c, ref, "reactivate", null, ref.email()));
                else out.add(new Planned(c, ref, "insert", null, ref.email()));
                continue;
            }
            String email = c.email() != null ? c.email() : c.code() + DEFAULT_DOMAIN;
            if (!canCreate() || c.firstName() == null) {
                out.add(new Planned(c, null, "unmatched", "not_found", null));
            } else if (taken.containsKey(email) || !newEmails.add(email)) {
                out.add(new Planned(c, null, "unmatched", "email_taken", email));
            } else {
                out.add(new Planned(c, null, "create", null, email));
            }
        }
        return out;
    }

    private AddResponse run(UUID sectionId, List<Candidate> cands, boolean dryRun, String reason, String via,
                            ParsedFile file, String ip) {
        if (dryRun) return response(true, sectionId, plan(sectionId, cands), Map.of());
        UUID actor = access.user().userId();
        record Done(List<Planned> plan, Map<Integer, UUID> created) {}
        Done done = tx.execute(t -> {
            repo.section(sectionId, true)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "ไม่พบกลุ่มเรียน"));
            List<Planned> plan = plan(sectionId, cands);
            Map<Integer, UUID> created = new LinkedHashMap<>();
            String hash = plan.stream().anyMatch(p -> p.action().equals("create")) ? encoder.encode(newStudentPassword) : null;
            for (Planned p : plan) {
                UUID studentId = p.ref() == null ? null : p.ref().studentId();
                switch (p.action()) {
                    case "create" -> {
                        studentId = repo.createStudent(p.c().code(), p.email(), p.c().title(), p.c().firstName(),
                                p.c().lastName(), hash);
                        created.put(p.c().rowNo(), studentId);
                        repo.insert(sectionId, studentId);
                        audit.log(actor, "student.create", "user", studentId, null,
                                toJson(Map.of("student_code", p.c().code(), "email", p.email(), "via", via)), ip);
                    }
                    case "insert" -> repo.insert(sectionId, studentId);
                    case "reactivate" -> repo.reactivate(sectionId, studentId);
                    default -> { continue; }
                }
                Map<String, Object> after = new LinkedHashMap<>();
                after.put("section_id", sectionId.toString());
                after.put("status", "active");
                after.put("via", via);
                if (reason != null) after.put("reason", reason);
                audit.log(actor, p.action().equals("reactivate") ? "enrollment.reactivate" : "enrollment.add",
                        "enrollment", studentId, p.action().equals("reactivate") ? "{\"status\": \"dropped\"}" : null,
                        toJson(after), ip);
            }
            if (file != null) {
                Map<String, Object> after = new LinkedHashMap<>();
                after.put("file_name", file.fileName());
                after.put("file_sha256", file.sha256());
                after.put("rows", plan.size());
                if (reason != null) after.put("reason", reason);
                audit.log(actor, "enrollment.import", "section", sectionId, null, toJson(after), ip);
            }
            return new Done(plan, created);
        });
        return response(false, sectionId, done.plan(), done.created());
    }

    private AddResponse response(boolean dryRun, UUID sectionId, List<Planned> plan, Map<Integer, UUID> created) {
        List<RowResult> rows = new ArrayList<>();
        int ins = 0, re = 0, cr = 0, sk = 0, un = 0, ap = 0;
        for (Planned p : plan) {
            boolean change = switch (p.action()) {
                case "insert" -> { ins++; yield true; }
                case "reactivate" -> { re++; yield true; }
                case "create" -> { cr++; yield true; }
                case "skip" -> { sk++; yield false; }
                default -> { un++; yield false; }
            };
            boolean applied = !dryRun && change;
            if (applied) ap++;
            UUID id = p.ref() != null ? p.ref().studentId() : created.get(p.c().rowNo());
            String name = p.ref() != null ? p.ref().fullName()
                    : p.c().firstName() == null ? null : p.c().firstName() + " " + p.c().lastName();
            rows.add(new RowResult(p.c().rowNo(), p.c().identifier(), id, p.c().code(), name, p.email(), p.action(),
                    p.reason(), applied));
        }
        return new AddResponse(dryRun, sectionId, canCreate(), rows,
                new Summary(plan.size(), ins, re, cr, sk, un, ap));
    }

    // ---------- helpers ----------

    private SectionInfo section(UUID sectionId) {
        return repo.section(sectionId, false)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "ไม่พบกลุ่มเรียน"));
    }

    private static String cell(DataRow row, Map<String, Integer> col, String name) {
        Integer i = col.get(name);
        return i == null ? null : row.cells().get(i);
    }

    private static String trim(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    /** เหตุผลไม่บังคับ (เก็บใน audit) · ≤ 500 */
    private static String reason(String raw) {
        String r = trim(raw);
        if (r != null && r.length() > REASON_MAX) throw bad("เหตุผลยาวได้ไม่เกิน " + REASON_MAX + " ตัวอักษร");
        return r;
    }

    private static long rowsOf(List<RowError> errors) {
        return errors.stream().map(RowError::rowNo).filter(Objects::nonNull).distinct().count();
    }

    private static byte[] xlsx() {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("students");
            CellStyle text = wb.createCellStyle();
            text.setDataFormat(wb.createDataFormat().getFormat("@"));
            CellStyle head = wb.createCellStyle();
            Font bold = wb.createFont();
            bold.setBold(true);
            head.setFont(bold);
            Row h = sheet.createRow(0);
            for (int c = 0; c < TEMPLATE_HEADERS.size(); c++) {
                h.createCell(c).setCellValue(TEMPLATE_HEADERS.get(c));
                h.getCell(c).setCellStyle(head);
                sheet.setDefaultColumnStyle(c, text);
                sheet.setColumnWidth(c, (c == 4 ? 32 : 16) * 256);
            }
            sheet.createFreezePane(0, 1);
            Sheet guide = wb.createSheet("วิธีกรอก");
            List<String> lines = List.of(
                    "วิธีกรอกรายชื่อนักศึกษา (ใช้ชีตแรก ห้ามแก้ชื่อหัวตาราง)",
                    "",
                    "student_code: รหัสนักศึกษา 11 หลัก (หรือใส่ email แทนได้)",
                    "email: <รหัส 11 หลัก>@... (ไม่บังคับ)",
                    "title / first_name / last_name: ใส่เมื่อนักศึกษายังไม่มีบัญชีในระบบ เพื่อสร้างบัญชีใหม่ (ถ้าระบบเปิดให้สร้าง)",
                    "นักศึกษาที่อยู่ในกลุ่มแล้ว = ข้าม · เคยถูกถอน = กลับเข้ากลุ่ม",
                    "ใช้ข้อมูลทดสอบเท่านั้น ห้ามใส่ข้อมูลนักศึกษาจริง");
            for (int i = 0; i < lines.size(); i++) guide.createRow(i).createCell(0).setCellValue(lines.get(i));
            guide.setColumnWidth(0, 100 * 256);
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private String toJson(Object o) {
        return json.writeValueAsString(o);
    }

    private static ResponseStatusException bad(String msg) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
    }
}
