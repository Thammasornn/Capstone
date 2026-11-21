package com.faculty.attendance.auth;

import java.util.UUID;

/** ผู้ใช้ที่ล็อกอินอยู่ (อ่านจาก JWT) role เป็นตัวพิมพ์เล็ก: student | lecturer | admin */
public record CurrentUser(UUID userId, String role, UUID studentId, UUID lecturerId) {
    public boolean isAdmin()    { return "admin".equals(role); }
    public boolean isLecturer() { return "lecturer".equals(role) && lecturerId != null; }
    public boolean isStudent()  { return "student".equals(role) && studentId != null; }
}
