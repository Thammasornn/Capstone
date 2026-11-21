package com.faculty.attendance.face;

import com.faculty.attendance.auth.AccessService;
import com.faculty.attendance.face.FaceDtos.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/** ต้องแนบ Authorization: Bearer <token> */
@RestController
@RequestMapping("/api/students/{studentId}/face")
public class FaceController {

    private final FaceRegistrationService service;
    private final AccessService access;

    public FaceController(FaceRegistrationService service, AccessService access) {
        this.service = service;
        this.access = access;
    }

    /**
     * นักศึกษาลงทะเบียนใบหน้าของตัวเองได้เท่านั้น (หรือ admin) ตรวจสิทธิ์ก่อนตรวจไฟล์ (ไม่ส่งไฟล์ = 400 ใน service)
     * ageConfirmed = checkbox ข้อ 2 บนหน้ายินยอม → consent_records.age_confirmed
     */
    @PostMapping(value = "/register", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public RegisterResponse register(@PathVariable UUID studentId,
                                     @RequestParam(value = "files", required = false) List<MultipartFile> files,
                                     @RequestParam(defaultValue = "false") boolean consent,
                                     @RequestParam(defaultValue = "v1") String consentTextVersion,
                                     @RequestParam(defaultValue = "false") boolean ageConfirmed,
                                     HttpServletRequest request) {
        access.requireFaceRegister(studentId);
        return service.register(studentId, files, consent, consentTextVersion, ageConfirmed,
                request.getRemoteAddr(), request.getHeader("User-Agent"));
    }

    /** เจ้าของบัญชี, admin หรืออาจารย์ที่สอนนักศึกษาคนนั้น */
    @GetMapping("/status")
    public StatusResponse status(@PathVariable UUID studentId) {
        access.requireFaceView(studentId);
        return service.status(studentId);
    }

    /** B7 ถอนความยินยอม + ลบข้อมูลใบหน้าทันที: เฉพาะนักศึกษาเจ้าของบัญชี หรือ admin */
    @DeleteMapping
    public WithdrawResponse withdraw(@PathVariable UUID studentId, HttpServletRequest request) {
        access.requireFaceRegister(studentId);
        return service.withdraw(studentId, access.user().userId(), request.getRemoteAddr(),
                request.getHeader("User-Agent"));
    }
}
