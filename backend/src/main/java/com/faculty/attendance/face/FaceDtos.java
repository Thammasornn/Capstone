package com.faculty.attendance.face;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public final class FaceDtos {
    private FaceDtos() {}

    /** response จาก AI Service (quality ใช้ตรวจเท่านั้น ไม่มีคอลัมน์ใน face_embeddings) */
    public record EmbeddingItem(List<Float> embedding, double quality) {}

    public record EmbeddingResponse(
            String model,
            @JsonProperty("model_version") String modelVersion,
            List<EmbeddingItem> items) {}

    /** response ของ API เรา */
    public record RegisterResponse(UUID studentId, int savedCount, String model) {}

    /** API 5.11 + consentVersion / consentedAt (B7) + needsReconsent (D6) */
    public record StatusResponse(UUID studentId, boolean registered, LocalDateTime registeredAt,
                                 int activeFaces, String consentStatus, String consentVersion,
                                 LocalDateTime consentedAt, boolean needsReconsent) {}

    /**
     * B7 DELETE /students/{studentId}/face · registered = false เสมอ · deletedCount = จำนวน embedding ที่ลบ
     * aiNotified = AI Service ยืนยันว่าลบ embedding ออกจากหน่วยความจำแล้ว (false = แจ้งไม่สำเร็จ ดู log)
     */
    public record WithdrawResponse(UUID studentId, boolean registered, LocalDateTime withdrawnAt,
                                   int deletedCount, boolean aiNotified) {}
}
