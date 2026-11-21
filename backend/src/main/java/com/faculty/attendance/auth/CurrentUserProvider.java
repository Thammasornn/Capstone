package com.faculty.attendance.auth;

import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class CurrentUserProvider {

    public CurrentUser get() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof Jwt jwt)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "ต้องเข้าสู่ระบบก่อน");
        }
        return new CurrentUser(UUID.fromString(jwt.getSubject()), jwt.getClaimAsString("role"),
                uuidClaim(jwt, "studentId"), uuidClaim(jwt, "lecturerId"));
    }

    private static UUID uuidClaim(Jwt jwt, String name) {
        String v = jwt.getClaimAsString(name);
        return v == null ? null : UUID.fromString(v);
    }
}
