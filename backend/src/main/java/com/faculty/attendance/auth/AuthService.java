package com.faculty.attendance.auth;

import com.faculty.attendance.auth.AuthDtos.*;
import com.faculty.attendance.auth.AuthRepository.UserRow;
import com.faculty.attendance.face.StudentFaceRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AuthService {

    private static final String BAD_CREDENTIALS = "อีเมลหรือรหัสผ่านไม่ถูกต้อง";

    private final AuthRepository repo;
    private final StudentFaceRepository faceRepo;
    private final PasswordEncoder encoder;
    private final JwtService jwt;
    private final CurrentUserProvider current;
    /** ใช้เทียบรหัสผ่านเมื่อไม่พบอีเมล เพื่อให้เวลาตอบใกล้เคียงกัน */
    private final String dummyHash;

    public AuthService(AuthRepository repo, StudentFaceRepository faceRepo, PasswordEncoder encoder,
                       JwtService jwt, CurrentUserProvider current) {
        this.repo = repo;
        this.faceRepo = faceRepo;
        this.encoder = encoder;
        this.jwt = jwt;
        this.current = current;
        this.dummyHash = encoder.encode("not-a-real-password");
    }

    public LoginResponse login(LoginRequest req) {
        if (req == null || req.email() == null || req.email().isBlank()
                || req.password() == null || req.password().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ต้องระบุอีเมลและรหัสผ่าน");
        }
        var found = repo.findUserByEmail(req.email().trim());
        boolean passwordOk = encoder.matches(req.password(),
                found.map(UserRow::passwordHash).orElse(dummyHash));
        if (found.isEmpty() || !passwordOk || !found.get().active()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, BAD_CREDENTIALS);
        }
        UserRow u = found.get();
        String role = u.role() == null ? "" : u.role().toLowerCase();
        // student_profiles.student_id = user_id · อาจารย์ใช้ user_id เป็น lecturerId (section_lecturers.lecturer_id)
        var studentId = "student".equals(role)
                ? repo.findStudentByUser(u.id()).map(AuthRepository.StudentRow::id).orElse(null) : null;
        var lecturerId = "lecturer".equals(role) ? u.id() : null;
        String token = jwt.issue(u.id(), role, studentId, lecturerId);
        repo.touchLastLogin(u.id());
        return new LoginResponse(token, "Bearer", jwt.ttlSeconds(), build(u));
    }

    public MeResponse me() {
        var cu = current.get();
        UserRow u = repo.findUserById(cu.userId())
                .filter(UserRow::active)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "บัญชีนี้ใช้งานไม่ได้"));
        return build(u);
    }

    private MeResponse build(UserRow u) {
        String role = u.role() == null ? null : u.role().toLowerCase();
        StudentProfile sp = "student".equals(role) ? repo.findStudentByUser(u.id()).map(s -> {
            var f = faceRepo.findStatus(s.id());
            return new StudentProfile(s.id(), s.code(), s.faculty(), s.program(), s.yearLevel(), s.entryYear(),
                    new FaceRegistration(f.registered(), f.registeredAt(), f.activeFaces()));
        }).orElse(null) : null;
        LecturerProfile lp = "lecturer".equals(role) ? new LecturerProfile(u.id(), null, null) : null;
        return new MeResponse(u.id(), u.email(), u.title(), u.firstName(), u.lastName(), role, sp, lp);
    }
}
