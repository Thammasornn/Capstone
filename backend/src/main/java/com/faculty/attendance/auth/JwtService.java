package com.faculty.attendance.auth;

import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

@Service
public class JwtService {

    private final JwtEncoder encoder;
    private final long ttlSeconds;

    public JwtService(JwtEncoder encoder, @Value("${app.jwt.expires-minutes}") long expiresMinutes) {
        this.encoder = encoder;
        this.ttlSeconds = expiresMinutes * 60;
    }

    public long ttlSeconds() {
        return ttlSeconds;
    }

    /** claims: sub=userId, role, studentId?, lecturerId? */
    public String issue(UUID userId, String role, UUID studentId, UUID lecturerId) {
        Instant now = Instant.now();
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer("attendance")
                .subject(userId.toString())
                .issuedAt(now)
                .expiresAt(now.plusSeconds(ttlSeconds))
                .claim("role", role);
        if (studentId != null) claims.claim("studentId", studentId.toString());
        if (lecturerId != null) claims.claim("lecturerId", lecturerId.toString());
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims.build())).getTokenValue();
    }
}
