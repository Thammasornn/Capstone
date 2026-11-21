package com.faculty.attendance.auth;

import java.time.LocalDateTime;
import java.util.UUID;

public final class AuthDtos {
    private AuthDtos() {}

    public record LoginRequest(String email, String password) {}

    /** expiresIn = วินาที */
    public record LoginResponse(String accessToken, String tokenType, long expiresIn, MeResponse user) {}

    /** สถานะการลงทะเบียนใบหน้าของนักศึกษา */
    public record FaceRegistration(boolean registered, LocalDateTime registeredAt, int activeFaces) {}

    public record StudentProfile(UUID studentId, String studentCode, String faculty, String department,
                                 Integer yearLevel, FaceRegistration faceRegistration) {}

    public record LecturerProfile(UUID lecturerId, String lecturerCode, String department) {}

    /** GET /api/me : student หรือ lecturer จะเป็น null ถ้าบัญชีไม่ใช่ role นั้น */
    public record MeResponse(UUID userId, String email, String firstName, String lastName, String role,
                             StudentProfile student, LecturerProfile lecturer) {}
}
