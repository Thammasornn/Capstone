package com.faculty.attendance.course;

import com.faculty.attendance.audit.AuditLogRepository;
import com.faculty.attendance.auth.AccessService;
import com.faculty.attendance.auth.CurrentUser;
import com.faculty.attendance.common.ClassDays;
import com.faculty.attendance.common.Times;
import com.faculty.attendance.course.CourseDtos.*;
import com.faculty.attendance.course.CourseRepository.SectionValues;
import java.time.Duration;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

/**
 * B5 จัดการรายวิชา + B6 สวิตช์กล้อง
 * สิทธิ์: admin ทุกวิชา · lecturer สร้างวิชาใหม่ได้ (เป็นผู้สอนเอง) และแก้ได้เฉพาะ section ที่ตัวเองสอน
 */
@Service
public class CourseService {

    private static final Pattern COURSE_CODE = Pattern.compile("^[A-Z]{3}[0-9]{3}$");
    private static final int DEFAULT_ON_TIME_MINUTES = 15;

    private final CourseRepository repo;
    private final AccessService access;
    private final AuditLogRepository audit;
    private final TransactionTemplate tx;
    private final JsonMapper json;

    public CourseService(CourseRepository repo, AccessService access, AuditLogRepository audit,
                         TransactionTemplate tx, JsonMapper json) {
        this.repo = repo;
        this.access = access;
        this.audit = audit;
        this.tx = tx;
        this.json = json;
    }

    public CourseDetail get(UUID courseId) {
        UUID lecturerId = access.requireCourseStaff(courseId);
        return detail(courseId, lecturerId);
    }

    public CourseDetail create(CourseInput in, String ip) {
        UUID lecturerScope = access.requireStaff();
        CurrentUser u = access.user();
        String code = courseCode(in);
        String name = courseName(in);
        if (in.sections() == null || in.sections().isEmpty()) throw bad("ต้องมีอย่างน้อย 1 กลุ่มเรียน");
        UUID semesterId = repo.currentOrNextSemester()
                .orElseThrow(() -> bad("ยังไม่มีภาคเรียนปัจจุบันหรือภาคถัดไปในระบบ (semesters)"));
        for (SectionInput s : in.sections()) {
            if (s.sectionId() != null) throw bad("รายวิชาใหม่ต้องไม่มี sectionId");
        }
        if (repo.courseCodeTaken(code, null)) throw bad("รหัสวิชา " + code + " มีอยู่แล้ว");

        List<Prepared> prepared = prepare(in.sections(), null, semesterId, lecturerScope);
        UUID courseId = tx.execute(t -> {
            UUID id = repo.insertCourse(code, name, u.userId());
            for (Prepared p : prepared) {
                saveSection(id, semesterId, p);
            }
            audit.log(u.userId(), "course.create", "course", id, null,
                    toJson(Map.of("course_code", code, "course_name", name, "sections", prepared.size())), ip);
            return id;
        });
        return detail(courseId, null);
    }

    public CourseDetail update(UUID courseId, CourseInput in, String ip) {
        UUID lecturerScope = access.requireCourseStaff(courseId);
        CurrentUser u = access.user();
        var before = repo.findCourse(courseId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "ไม่พบรายวิชา"));
        String code = courseCode(in);
        String name = courseName(in);
        if (in.sections() == null || in.sections().isEmpty()) throw bad("ต้องมีอย่างน้อย 1 กลุ่มเรียน");
        if (repo.courseCodeTaken(code, courseId)) throw bad("รหัสวิชา " + code + " มีอยู่แล้ว");
        UUID newSemesterId = repo.currentOrNextSemester().orElse(null);

        // section เดิมต้องเป็นของวิชานี้ และ lecturer แก้ได้เฉพาะ section ที่ตัวเองสอน
        Map<UUID, UUID> semesterOf = new LinkedHashMap<>();
        for (SectionInput s : in.sections()) {
            if (s.sectionId() == null) continue;
            var ref = repo.sectionOfCourse(s.sectionId(), courseId)
                    .orElseThrow(() -> bad("ไม่พบกลุ่มเรียน " + s.sectionId() + " ในรายวิชานี้"));
            if (lecturerScope != null) access.requireSectionStaff(s.sectionId());
            if (semesterOf.put(s.sectionId(), ref.semesterId()) != null) throw bad("ส่งกลุ่มเรียนซ้ำกัน");
        }
        if (newSemesterId == null && in.sections().stream().anyMatch(s -> s.sectionId() == null)) {
            throw bad("ยังไม่มีภาคเรียนปัจจุบันหรือภาคถัดไปในระบบ (semesters)");
        }

        List<Prepared> prepared = new ArrayList<>();
        // ตรวจทีละภาค (กลุ่มเดิมอาจอยู่คนละภาคกับกลุ่มใหม่)
        Map<UUID, List<SectionInput>> bySemester = new LinkedHashMap<>();
        for (SectionInput s : in.sections()) {
            UUID sem = s.sectionId() == null ? newSemesterId : semesterOf.get(s.sectionId());
            bySemester.computeIfAbsent(sem, k -> new ArrayList<>()).add(s);
        }
        bySemester.forEach((sem, list) -> prepared.addAll(prepare(list, courseId, sem, lecturerScope)));

        List<SessionNotChanged> notChanged = tx.execute(t -> {
            List<SessionNotChanged> kept = new ArrayList<>();
            repo.updateCourse(courseId, code, name);
            for (Prepared p : prepared) {
                // กลุ่มเดิมที่ตารางเรียนเปลี่ยน: คาบที่ระบบไม่ได้แก้ตาม (เริ่มแล้ว / มีข้อมูล) ต้องแจ้งผู้ใช้ ไม่เงียบ
                String sigBefore = p.sectionId() == null ? null : repo.sectionSignature(p.sectionId());
                UUID id = saveSection(courseId, p.semesterId(), p);
                if (sigBefore != null && !sigBefore.equals(repo.sectionSignature(id))) {
                    kept.addAll(repo.sessionsNotMatchingSection(id));
                }
            }
            audit.log(u.userId(), "course.update", "course", courseId,
                    toJson(Map.of("course_code", before.courseCode(), "course_name", before.courseName())),
                    toJson(Map.of("course_code", code, "course_name", name, "sections", prepared.size())), ip);
            return kept;
        });
        CourseDetail d = detail(courseId, lecturerScope);
        return new CourseDetail(d.courseId(), d.courseCode(), d.courseName(), d.sections(), notChanged);
    }

    /** Q5 soft delete · lecturer ลบได้เมื่อสอนทุกกลุ่มของวิชานั้น */
    public void delete(UUID courseId, String ip) {
        UUID lecturerScope = access.requireCourseStaff(courseId);
        CurrentUser u = access.user();
        repo.findCourse(courseId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "ไม่พบรายวิชา"));
        if (lecturerScope != null && !repo.teachesAllSections(courseId, lecturerScope)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "ลบไม่ได้ เพราะมีกลุ่มเรียนที่อาจารย์ท่านอื่นสอน กรุณาติดต่อผู้ดูแลระบบ");
        }
        tx.executeWithoutResult(t -> {
            var deletedAt = repo.softDeleteCourse(courseId);
            audit.log(u.userId(), "course.delete", "course", courseId, "{\"deleted_at\": null}",
                    toJson(Map.of("deleted_at", Times.local(deletedAt).toString())), ip);
        });
    }

    /** B6 · มีผลกับกล้องทันที (AttendanceService อ่านค่านี้ทุกครั้งที่กล้องส่งผล) */
    public CameraToggleResponse setCameraAttendance(UUID sectionId, CameraToggleRequest in, String ip) {
        access.requireSectionStaff(sectionId);
        if (in == null || in.enabled() == null) throw bad("ค่า enabled ต้องเป็น true หรือ false");
        boolean enabled = in.enabled();
        UUID actor = access.user().userId();
        Boolean old = tx.execute(t -> {
            Boolean prev = repo.setCameraAttendance(sectionId, enabled);
            if (prev != null && prev != enabled) {
                audit.log(actor, "section.camera_toggle", "section", sectionId,
                        "{\"camera_attendance_enabled\": " + prev + "}",
                        "{\"camera_attendance_enabled\": " + enabled + "}", ip);
            }
            return prev;
        });
        if (old == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "ไม่พบกลุ่มเรียน");
        return new CameraToggleResponse(sectionId, enabled);
    }

    // ---------- validation ----------

    private record Prepared(UUID sectionId, UUID semesterId, SectionValues values, Integer[] days, UUID lecturerId) {}

    /**
     * ตรวจทุก section ก่อนเขียน DB (CHECK ใน sections จะไม่ล้มกลางทาง)
     * ห้องชน: เทียบกับ section อื่นใน DB ภาคเดียวกัน และ section อื่นใน request เดียวกัน
     */
    private List<Prepared> prepare(List<SectionInput> inputs, UUID courseId, UUID semesterId, UUID lecturerScope) {
        List<Prepared> out = new ArrayList<>();
        Set<String> codes = new HashSet<>();
        UUID[] inRequest = inputs.stream().map(SectionInput::sectionId).filter(Objects::nonNull).toArray(UUID[]::new);
        for (SectionInput s : inputs) {
            if (s == null) throw bad("ข้อมูลกลุ่มเรียนไม่ถูกต้อง");
            String code = s.sectionCode() == null ? "" : s.sectionCode().trim();
            String label = "กลุ่ม " + (code.isEmpty() ? "-" : code);
            if (code.isEmpty()) throw bad("กรุณากรอกรหัสกลุ่มเรียน");
            if (code.length() > 10) throw bad(label + ": รหัสกลุ่มเรียนยาวได้ไม่เกิน 10 ตัวอักษร");
            if (!codes.add(code)) throw bad(label + ": รหัสกลุ่มเรียนซ้ำกัน");
            if (courseId != null && repo.sectionCodeTaken(courseId, semesterId, code, s.sectionId())) {
                throw bad(label + ": รหัสกลุ่มเรียนนี้มีอยู่แล้วในรายวิชา");
            }
            if (s.roomId() == null || repo.roomCode(s.roomId()).isEmpty()) throw bad(label + ": ไม่พบห้องเรียน");

            Set<Integer> daySet = new TreeSet<>();
            if (s.classDays() != null) {
                for (String d : s.classDays()) {
                    int iso = ClassDays.toIso(d);
                    if (iso < 0) throw bad(label + ": วันเรียนไม่ถูกต้อง (" + d + ")");
                    daySet.add(iso);
                }
            }
            if (daySet.isEmpty()) throw bad(label + ": กรุณาเลือกวันเรียน");
            Integer[] days = daySet.toArray(Integer[]::new);

            LocalTime start = s.startTime(), end = s.endTime(), opens = s.checkInOpens(), closes = s.checkInCloses();
            if (start == null || end == null) throw bad(label + ": กรุณากรอกเวลาเรียน");
            if (!end.isAfter(start)) throw bad(label + ": เวลาเลิกเรียนต้องหลังเวลาเริ่ม");
            if (opens == null || closes == null || !closes.isAfter(opens)) throw bad(label + ": ช่วงเช็คชื่อไม่ถูกต้อง");
            if (opens.isBefore(start) || closes.isAfter(end)) throw bad(label + ": ช่วงเช็คชื่อต้องอยู่ในเวลาเรียน");

            LocalTime lateUntil = s.lateUntil();
            if (lateUntil != null && (!lateUntil.isAfter(opens) || lateUntil.isAfter(closes))) {
                throw bad(label + ": เวลาสายสุดท้ายต้องอยู่ในช่วงเช็คชื่อ");
            }
            long window = Duration.between(opens, lateUntil != null ? lateUntil : closes).toMinutes();
            int onTime;
            if (s.onTimeMinutes() == null) {
                onTime = (int) Math.min(DEFAULT_ON_TIME_MINUTES, window);
            } else {
                onTime = s.onTimeMinutes();
                if (onTime < 0 || onTime > window) {
                    throw bad(label + ": เวลาตรงเวลาต้องอยู่ระหว่าง 0–" + window + " นาที");
                }
            }

            UUID lecturerId = lecturer(s, lecturerScope, label);

            String clash = repo.roomClash(semesterId, s.roomId(), days, start, end, inRequest).orElse(null);
            if (clash != null) {
                throw bad(label + ": ห้อง " + repo.roomCode(s.roomId()).orElse("") + " มีกลุ่ม " + clash
                        + " ใช้ในช่วงเวลานี้แล้ว");
            }
            for (Prepared p : out) {
                if (p.values().roomId().equals(s.roomId())
                        && p.values().startTime().isBefore(end) && start.isBefore(p.values().endTime())
                        && overlaps(p.days(), days)) {
                    throw bad(label + ": ห้องชนกับกลุ่ม " + p.values().sectionCode() + " ในรายวิชานี้");
                }
            }

            out.add(new Prepared(s.sectionId(), semesterId,
                    new SectionValues(code, s.roomId(), start, end, opens, closes, onTime, lateUntil,
                            s.cameraAttendanceEnabled() == null || s.cameraAttendanceEnabled()),
                    days, lecturerId));
        }
        return out;
    }

    /** admin: ต้องระบุเมื่อสร้างกลุ่มใหม่ · lecturer: ได้แค่ตัวเอง · null ตอนแก้ไข = ไม่เปลี่ยนผู้สอน */
    private UUID lecturer(SectionInput s, UUID lecturerScope, String label) {
        if (lecturerScope != null) {
            if (s.lecturerId() != null && !s.lecturerId().equals(lecturerScope)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, label + ": อาจารย์กำหนดผู้สอนเป็นคนอื่นไม่ได้");
            }
            return s.sectionId() == null ? lecturerScope : s.lecturerId();
        }
        if (s.lecturerId() == null) {
            if (s.sectionId() == null) throw bad(label + ": กรุณาเลือกอาจารย์ผู้สอน");
            return null;
        }
        if (!repo.isActiveLecturer(s.lecturerId())) throw bad(label + ": ไม่พบอาจารย์ผู้สอน");
        return s.lecturerId();
    }

    private UUID saveSection(UUID courseId, UUID semesterId, Prepared p) {
        UUID sectionId = p.sectionId();
        if (sectionId == null) {
            sectionId = repo.insertSection(courseId, semesterId, p.values());
        } else {
            repo.updateSection(sectionId, p.values());
        }
        repo.replaceClassDays(sectionId, p.days());
        if (p.lecturerId() != null) repo.setPrimaryLecturer(sectionId, p.lecturerId());
        repo.regenerateSessions(sectionId);
        return sectionId;
    }

    private static boolean overlaps(Integer[] a, Integer[] b) {
        for (Integer x : a) for (Integer y : b) if (x.equals(y)) return true;
        return false;
    }

    private static String courseCode(CourseInput in) {
        if (in == null) throw bad("ข้อมูลรายวิชาไม่ถูกต้อง");
        String code = in.courseCode() == null ? "" : in.courseCode().trim().toUpperCase();
        if (code.isEmpty() || in.courseName() == null || in.courseName().isBlank()) {
            throw bad("กรุณากรอกรหัสและชื่อรายวิชา");
        }
        if (!COURSE_CODE.matcher(code).matches()) {
            throw bad("รหัสวิชาต้องเป็นตัวอักษร 3 ตัวตามด้วยตัวเลข 3 ตัว เช่น INT101");
        }
        return code;
    }

    private static String courseName(CourseInput in) {
        String name = in.courseName().trim();
        if (name.length() > 200) throw bad("ชื่อรายวิชายาวได้ไม่เกิน 200 ตัวอักษร");
        return name;
    }

    private CourseDetail detail(UUID courseId, UUID lecturerId) {
        var c = repo.findCourse(courseId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "ไม่พบรายวิชา"));
        return new CourseDetail(c.courseId(), c.courseCode(), c.courseName(), repo.sections(courseId, lecturerId),
                List.of());
    }

    private String toJson(Object o) {
        return json.writeValueAsString(o);
    }

    private static ResponseStatusException bad(String msg) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
    }
}
