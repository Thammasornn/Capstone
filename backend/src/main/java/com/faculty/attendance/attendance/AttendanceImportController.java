package com.faculty.attendance.attendance;

import com.faculty.attendance.attendance.AttendanceImportService.ImportParams;
import com.faculty.attendance.attendance.AttendanceImportService.TemplateFile;
import com.faculty.attendance.attendance.ImportDtos.HistoryItem;
import com.faculty.attendance.attendance.ImportDtos.ImportDetail;
import com.faculty.attendance.attendance.ImportDtos.ImportResponse;
import com.faculty.attendance.attendance.ImportDtos.InspectResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/** นำเข้าผลเช็คชื่อจากไฟล์ (คลาสออนไซต์) · admin / อาจารย์ผู้สอน (requireSession) · กติกาอยู่ที่ AttendanceImportService */
@RestController
@RequestMapping("/api/class-sessions/{sessionId}/attendance")
public class AttendanceImportController {

    private final AttendanceImportService service;

    public AttendanceImportController(AttendanceImportService service) {
        this.service = service;
    }

    /** template (xlsx | csv) มีรายชื่อนักศึกษาของกลุ่มเรียน */
    @GetMapping("/import-template")
    public ResponseEntity<byte[]> template(@PathVariable UUID sessionId,
                                           @RequestParam(defaultValue = "xlsx") String format) {
        TemplateFile f = service.template(sessionId, format);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(f.fileName(), StandardCharsets.UTF_8).build().toString())
                .contentType(MediaType.parseMediaType(f.contentType()))
                .body(f.bytes());
    }

    /** อ่านหัวตาราง / ตัวอย่างแถว / ค่าที่ไม่ซ้ำ ให้ผู้ใช้จับคู่คอลัมน์ (ไม่บันทึก DB) */
    @PostMapping(value = "/import/inspect", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public InspectResponse inspect(@PathVariable UUID sessionId,
                                   @RequestParam(value = "file", required = false) MultipartFile file) {
        return service.inspect(sessionId, file);
    }

    /** dryRun = true (ค่าเริ่มต้น) ดูผลก่อน · false = บันทึก */
    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ImportResponse importFile(@PathVariable UUID sessionId,
                                     @RequestParam(value = "file", required = false) MultipartFile file,
                                     @RequestParam(required = false) String mode,
                                     @RequestParam(required = false) String method,
                                     @RequestParam(required = false) String reason,
                                     @RequestParam(defaultValue = "true") boolean dryRun,
                                     @RequestParam(defaultValue = "false") boolean overrideCamera,
                                     @RequestParam(defaultValue = "false") boolean overrideManual,
                                     @RequestParam(required = false) String mapping,
                                     HttpServletRequest request) {
        return service.importFile(sessionId, file,
                new ImportParams(mode, method, reason, dryRun, overrideCamera, overrideManual, mapping),
                request.getRemoteAddr());
    }

    @GetMapping("/imports")
    public List<HistoryItem> history(@PathVariable UUID sessionId) {
        return service.history(sessionId);
    }

    @GetMapping("/imports/{importId}")
    public ImportDetail detail(@PathVariable UUID sessionId, @PathVariable UUID importId) {
        return service.detail(sessionId, importId);
    }
}
