package com.faculty.attendance.face;

import com.faculty.attendance.audit.AuditLogRepository;
import com.faculty.attendance.face.FaceDtos.*;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
public class FaceRegistrationService {

    private static final int MIN_IMAGES = 3;
    private static final int MAX_IMAGES = 5;
    private static final long MAX_BYTES = 5L * 1024 * 1024;
    private static final int EMBEDDING_DIM = 128;

    private final AiServiceClient ai;
    private final StudentFaceRepository repo;
    private final EmbeddingCipher cipher;
    private final AuditLogRepository audit;
    private final TransactionTemplate tx;

    public FaceRegistrationService(AiServiceClient ai, StudentFaceRepository repo, EmbeddingCipher cipher,
                                   AuditLogRepository audit, TransactionTemplate tx) {
        this.ai = ai;
        this.repo = repo;
        this.cipher = cipher;
        this.audit = audit;
        this.tx = tx;
    }

    /**
     * เรียก AI ก่อน (นอก transaction เพื่อไม่ถือ DB connection ระหว่างรอ) แล้วเขียน DB รวดเดียว:
     * INSERT consent_records (granted) → DELETE face_embeddings เดิม → INSERT ชุดใหม่ (เข้ารหัส)
     */
    public RegisterResponse register(UUID studentId, List<MultipartFile> files, boolean consent,
                                     String consentTextVersion, boolean ageConfirmed, String ip,
                                     String userAgent) {
        if (!consent) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ต้องยินยอมการเก็บข้อมูลใบหน้าก่อน");
        }
        validateFiles(files);
        if (!repo.studentExists(studentId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "ไม่พบนักศึกษา");
        }
        String current = repo.currentConsentVersion()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                        "ยังไม่มีข้อความยินยอมฉบับที่มีผล (consent_versions)"));
        if (!current.equals(consentTextVersion)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "ข้อความยินยอมไม่ใช่ฉบับปัจจุบัน (" + current + ") กรุณาโหลดหน้าใหม่แล้วยินยอมอีกครั้ง");
        }

        EmbeddingResponse res = ai.extract(files);
        if (res == null || res.items() == null || res.items().size() != files.size()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "ผลลัพธ์จาก AI Service ไม่ครบ");
        }
        if (res.modelVersion() == null || res.modelVersion().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "AI Service ไม่ได้ส่ง model_version");
        }
        List<byte[]> sealed = new ArrayList<>();
        for (EmbeddingItem it : res.items()) {
            if (it.embedding() == null || it.embedding().size() != EMBEDDING_DIM) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "ขนาด embedding ไม่ถูกต้อง");
            }
            sealed.add(cipher.encrypt(studentId, it.embedding()));
        }
        String model = res.model() == null || res.model().isBlank() ? "sface" : res.model();

        tx.executeWithoutResult(status -> {
            repo.lockStudent(studentId);
            UUID consentId = repo.insertGrantedConsent(studentId, current, ageConfirmed, ip, userAgent);
            repo.deleteAll(studentId);                           // register ใหม่ = แทนที่ของเดิม (ลบจริง)
            for (byte[] e : sealed) {
                repo.insert(studentId, consentId, e, model, res.modelVersion());
            }
        });

        return new RegisterResponse(studentId, sealed.size(), model);
    }

    public StatusResponse status(UUID studentId) {
        if (!repo.studentExists(studentId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "ไม่พบนักศึกษา");
        }
        var s = repo.findStatus(studentId);
        return new StatusResponse(studentId, s.registered(), s.registeredAt(), s.activeFaces(),
                s.consentStatus(), s.consentVersion(), s.consentedAt(), s.needsReconsent());
    }

    /**
     * B7 ถอนความยินยอม (docs/database/database-design.md · PDPA ข้อ 2) ทั้งหมดใน transaction เดียว:
     * INSERT consent_records (withdrawn) → DELETE face_embeddings → INSERT data_deletion_jobs (completed_at = now())
     * → INSERT audit_logs (face.consent_withdrawn) · ประวัติเช็คชื่อไม่ลบ (ไม่ใช่ข้อมูลใบหน้า)
     * TODO: แจ้ง AI Service ให้ล้าง cache ของนักศึกษาคนนี้ เมื่อ AI Service มี endpoint สำหรับเรื่องนี้
     */
    public WithdrawResponse withdraw(UUID studentId, UUID actorId, String ip, String userAgent) {
        if (!repo.studentExists(studentId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "ไม่พบนักศึกษา");
        }
        return tx.execute(status -> {
            repo.lockStudent(studentId);
            var latest = repo.latestConsent(studentId)
                    .filter(c -> "granted".equals(c.action()))
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
                            "ยังไม่ได้ให้ความยินยอม หรือถอนความยินยอมไปแล้ว"));
            var withdrawnAt = repo.insertWithdrawnConsent(studentId, latest.version(), latest.ageConfirmed(),
                    ip, userAgent);
            int deleted = repo.deleteAll(studentId);
            repo.insertCompletedDeletionJob(studentId, deleted);
            audit.log(actorId, "face.consent_withdrawn", "student", studentId,
                    "{\"consentId\":\"" + latest.consentId() + "\",\"embeddingCount\":" + deleted + "}",
                    "{\"consentStatus\":\"withdrawn\",\"embeddingCount\":0}", ip);
            return new WithdrawResponse(studentId, false,
                    withdrawnAt.atZoneSameInstant(ZoneId.of("Asia/Bangkok")).toLocalDateTime(), deleted);
        });
    }

    private void validateFiles(List<MultipartFile> files) {
        if (files == null || files.size() < MIN_IMAGES || files.size() > MAX_IMAGES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "ต้องส่งภาพ " + MIN_IMAGES + "-" + MAX_IMAGES + " ภาพ");
        }
        for (MultipartFile f : files) {
            String ct = f.getContentType();
            if (f.isEmpty() || f.getSize() > MAX_BYTES
                    || ct == null || !(ct.equals("image/jpeg") || ct.equals("image/png"))) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "ไฟล์ต้องเป็น JPEG/PNG และไม่เกิน 5MB");
            }
        }
    }
}
