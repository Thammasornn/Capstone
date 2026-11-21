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
        if (found.isEmpty() || !passwordOk || !"active".equalsIgnoreCase(found.get().status())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, BAD_CREDENTIALS);
        }
        UserRow u = found.get();
        var student = repo.findStudentByUser(u.id());
        var lecturer = repo.findLecturerByUser(u.id());
        String role = u.role() == null ? "" : u.role().toLowerCase();
        String token = jwt.issue(u.id(), role,
                student.map(AuthRepository.StudentRow::id).orElse(null),
                lecturer.map(AuthRepository.LecturerRow::id).orElse(null));
        return new LoginResponse(token, "Bearer", jwt.ttlSeconds(), build(u));
    }

    public MeResponse me() {
        var cu = current.get();
        UserRow u = repo.findUserById(cu.userId())
                .filter(x -> "active".equalsIgnoreCase(x.status()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "บัญชีนี้ใช้งานไม่ได้"));
        return build(u);
    }

    private MeResponse build(UserRow u) {
        StudentProfile sp = repo.findStudentByUser(u.id()).map(s -> {
            var e = faceRepo.findEnrollment(s.id());
            return new StudentProfile(s.id(), s.code(), s.faculty(), s.department(), s.yearLevel(),
                    new FaceRegistration(e.registered(), e.registeredAt(), faceRepo.countActiveFaces(s.id())));
        }).orElse(null);
        LecturerProfile lp = repo.findLecturerByUser(u.id())
                .map(l -> new LecturerProfile(l.id(), l.code(), l.department())).orElse(null);
        return new MeResponse(u.id(), u.email(), u.firstName(), u.lastName(),
                u.role() == null ? null : u.role().toLowerCase(), sp, lp);
    }
}
