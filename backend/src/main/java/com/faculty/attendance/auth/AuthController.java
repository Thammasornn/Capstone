package com.faculty.attendance.auth;

import com.faculty.attendance.auth.AuthDtos.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class AuthController {

    private final AuthService service;

    public AuthController(AuthService service) {
        this.service = service;
    }

    /** ไม่ต้องใช้ token */
    @PostMapping("/auth/login")
    public LoginResponse login(@RequestBody LoginRequest request) {
        return service.login(request);
    }

    /** โปรไฟล์ของผู้ที่ล็อกอิน: ใช้ได้ทั้ง student / lecturer / admin */
    @GetMapping("/me")
    public MeResponse me() {
        return service.me();
    }
}
