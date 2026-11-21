package com.faculty.attendance.face;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * เข้ารหัส face embedding ก่อนเก็บลง face_embeddings.embedding (Q7: AES-GCM ระดับแอป)
 *
 * รูปแบบ bytea = nonce 12 ไบต์ + ciphertext + tag 16 ไบต์
 *  plaintext = float32 little-endian ต่อกัน (SFace 128 มิติ = 512 ไบต์ → รวม 540 ไบต์)
 *  AAD       = student_id (UUID string) กันการสลับ embedding ไปเป็นของนักศึกษาคนอื่น
 * AI Service ต้องถอดรหัสด้วยกุญแจและรูปแบบเดียวกันนี้
 *
 * กุญแจ: FACE_EMBEDDING_KEY = base64 ของ 32 ไบต์ (openssl rand -base64 32) เก็บใน K3s Secret
 */
@Component
public class EmbeddingCipher {

    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    public EmbeddingCipher(@Value("${app.face.embedding-key:}") String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            throw new IllegalStateException("ต้องตั้ง FACE_EMBEDDING_KEY (base64 ของ 32 ไบต์) ก่อนเปิดระบบ");
        }
        byte[] raw = Base64.getDecoder().decode(base64Key.trim());
        if (raw.length != 32) {
            throw new IllegalStateException("FACE_EMBEDDING_KEY ต้องเป็น 32 ไบต์ (AES-256)");
        }
        this.key = new SecretKeySpec(raw, "AES");
    }

    public byte[] encrypt(UUID studentId, List<Float> vector) {
        ByteBuffer plain = ByteBuffer.allocate(vector.size() * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        vector.forEach(plain::putFloat);
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            c.updateAAD(studentId.toString().getBytes(StandardCharsets.US_ASCII));
            byte[] sealed = c.doFinal(plain.array());
            return ByteBuffer.allocate(NONCE_BYTES + sealed.length).put(nonce).put(sealed).array();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("เข้ารหัส embedding ไม่สำเร็จ", e);
        }
    }
}
