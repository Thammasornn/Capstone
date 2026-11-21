package com.faculty.attendance.face;

import com.faculty.attendance.face.FaceDtos.EmbeddingResponse;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Component
public class AiServiceClient {

    /** body ของ error 422 จาก AI Service: {"detail": "..."} */
    record ErrorBody(String detail) {}

    private static final Logger log = LoggerFactory.getLogger(AiServiceClient.class);

    private final RestClient client;
    private final String internalKey;

    public AiServiceClient(RestClient aiRestClient, @Value("${app.internal.api-key:}") String internalKey) {
        this.client = aiRestClient;
        this.internalKey = internalKey;
    }

    /**
     * B7: สั่ง AI Service ลบ embedding ของนักศึกษาออกจากหน่วยความจำ (DELETE /students/{id}/embeddings)
     * เรียกหลัง commit แล้ว · ล้มเหลวไม่ทำให้การถอนล้ม (DB ลบแล้ว AI โหลดใหม่จาก DB จะไม่เจอ) แต่ log ไว้
     * @return true = AI ยืนยันว่าลบแล้ว
     */
    public boolean forgetStudent(UUID studentId) {
        try {
            client.delete()
                    .uri("/students/{id}/embeddings", studentId)
                    .header("X-Internal-Key", internalKey)
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (RestClientException e) {
            log.warn("แจ้ง AI Service ให้ลบ embedding ของนักศึกษา {} ไม่สำเร็จ: {}", studentId, e.getMessage());
            return false;
        }
    }

    public EmbeddingResponse extract(List<MultipartFile> files) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        for (int i = 0; i < files.size(); i++) {
            MultipartFile f = files.get(i);
            final String name = f.getOriginalFilename() != null ? f.getOriginalFilename() : "face_" + i + ".jpg";
            try {
                body.add("files", new ByteArrayResource(f.getBytes()) {
                    @Override public String getFilename() { return name; }
                });
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        try {
            return client.post()
                    .uri("/embedding")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(body)
                    .exchange((req, res) -> {
                        // AI Service ปฏิเสธภาพ (422) -> ส่งเหตุผลกลับไปให้ผู้ใช้
                        if (res.getStatusCode().value() == 422) {
                            ErrorBody err = res.bodyTo(ErrorBody.class);
                            String detail = err != null && err.detail() != null
                                    ? err.detail() : "ภาพไม่ผ่านการตรวจสอบ";
                            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, detail);
                        }
                        if (res.getStatusCode().isError()) {
                            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "AI Service ไม่พร้อมใช้งาน");
                        }
                        return res.bodyTo(EmbeddingResponse.class);
                    });
        } catch (ResponseStatusException e) {
            throw e;
        } catch (RestClientException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "AI Service ไม่พร้อมใช้งาน", e);
        }
    }
}
