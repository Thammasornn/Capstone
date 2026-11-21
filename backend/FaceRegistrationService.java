package com.faculty.attendance.face;

import com.faculty.attendance.face.FaceDtos.*;
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
    private final TransactionTemplate tx;

    public FaceRegistrationService(AiServiceClient ai, StudentFaceRepository repo, TransactionTemplate tx) {
        this.ai = ai;
        this.repo = repo;
        this.tx = tx;
    }

    /** เรียก AI ก่อน (นอก transaction เพื่อไม่ถือ DB connection ระหว่างรอ) แล้วค่อยเขียน DB รวดเดียว */
    public RegisterResponse register(UUID studentId, List<MultipartFile> files, boolean consent,
                                     String consentTextVersion, String ip) {
        if (!consent) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ต้องยินยอมการเก็บข้อมูลใบหน้าก่อน");
        }
        validateFiles(files);
        if (!repo.studentExists(studentId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "ไม่พบนักศึกษา");
        }

        EmbeddingResponse res = ai.extract(files);
        if (res == null || res.items() == null || res.items().size() != files.size()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "ผลลัพธ์จาก AI Service ไม่ครบ");
        }
        for (EmbeddingItem it : res.items()) {
            if (it.embedding() == null || it.embedding().size() != EMBEDDING_DIM) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "ขนาด embedding ไม่ถูกต้อง");
            }
        }

        tx.executeWithoutResult(status -> {
            repo.deactivateAll(studentId);                       // register ใหม่ = แทนที่ของเดิม
            for (EmbeddingItem it : res.items()) {
                repo.insert(studentId, it, res.model(), res.modelVersion());
            }
            repo.recordConsent(studentId, consentTextVersion, ip);
            repo.upsertRegistered(studentId);
        });

        return new RegisterResponse(studentId, res.items().size(), res.model());
    }

    public StatusResponse status(UUID studentId) {
        if (!repo.studentExists(studentId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "ไม่พบนักศึกษา");
        }
        var e = repo.findEnrollment(studentId);
        return new StatusResponse(studentId, e.registered(), e.registeredAt(),
                repo.countActiveFaces(studentId));
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
