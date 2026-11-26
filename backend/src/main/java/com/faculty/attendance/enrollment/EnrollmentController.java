package com.faculty.attendance.enrollment;

import com.faculty.attendance.enrollment.EnrollmentService.AddRequest;
import com.faculty.attendance.enrollment.EnrollmentService.AddResponse;
import com.faculty.attendance.enrollment.EnrollmentService.DropRequest;
import com.faculty.attendance.enrollment.EnrollmentService.DropResponse;
import com.faculty.attendance.enrollment.EnrollmentService.EnrollmentList;
import com.faculty.attendance.enrollment.EnrollmentService.TemplateFile;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/** นักศึกษาในกลุ่มเรียน · admin / อาจารย์ผู้สอนกลุ่มนั้น · กติกาอยู่ที่ EnrollmentService */
@RestController
@RequestMapping("/api/sections/{sectionId}/enrollments")
public class EnrollmentController {

    private final EnrollmentService service;

    public EnrollmentController(EnrollmentService service) {
        this.service = service;
    }

    @GetMapping
    public EnrollmentList list(@PathVariable UUID sectionId, @RequestParam(required = false) String status) {
        return service.list(sectionId, status);
    }

    /** ป้อนเอง: 1 คนหรือหลายคน · dryRun = ดูผลก่อน (ค่าเริ่มต้น false) */
    @PostMapping
    public AddResponse add(@PathVariable UUID sectionId, @RequestBody(required = false) AddRequest body,
                           HttpServletRequest request) {
        return service.add(sectionId, body, request.getRemoteAddr());
    }

    /** ถอน (soft) · ผลเช็คชื่อเก็บไว้ */
    @PostMapping("/{studentId}/drop")
    public DropResponse drop(@PathVariable UUID sectionId, @PathVariable UUID studentId,
                             @RequestBody(required = false) DropRequest body, HttpServletRequest request) {
        return service.drop(sectionId, studentId, body, request.getRemoteAddr());
    }

    @GetMapping("/import-template")
    public ResponseEntity<byte[]> template(@PathVariable UUID sectionId,
                                           @RequestParam(defaultValue = "xlsx") String format) {
        TemplateFile f = service.template(sectionId, format);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(f.fileName(), StandardCharsets.UTF_8).build().toString())
                .contentType(MediaType.parseMediaType(f.contentType()))
                .body(f.bytes());
    }

    /** นำเข้าไฟล์ CSV / TXT / XLSX · dryRun = true (ค่าเริ่มต้น) ดูผลก่อน · false = บันทึก */
    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public AddResponse importFile(@PathVariable UUID sectionId,
                                  @RequestParam(value = "file", required = false) MultipartFile file,
                                  @RequestParam(defaultValue = "true") boolean dryRun,
                                  @RequestParam(required = false) String reason, HttpServletRequest request) {
        return service.importFile(sectionId, file, dryRun, reason, request.getRemoteAddr());
    }
}
