package com.faculty.attendance.internal;

import com.faculty.attendance.attendance.AttendanceDtos.*;
import com.faculty.attendance.attendance.AttendanceRepository;
import com.faculty.attendance.attendance.AttendanceService;
import com.faculty.attendance.common.Times;
import com.faculty.attendance.internal.CameraFeedRepository.CameraFeed;
import com.faculty.attendance.room.RoomDtos.CameraEvent;
import com.faculty.attendance.room.RoomDtos.FacesEvent;
import com.faculty.attendance.room.RoomEventHub;
import com.faculty.attendance.room.RoomRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * AI Service -> backend (ไม่ผ่าน JWT) ต้องแนบ header X-Internal-Key = app.internal.api-key
 * ไม่ควรเปิดออกนอก cluster: ingress ต้องไม่ route /api/internal/**
 */
@RestController
@RequestMapping("/api/internal")
public class InternalController {

    private static final Set<String> CAMERA_STATUSES = Set.of("live", "no_signal");

    private final byte[] key;
    private final RoomRepository rooms;
    private final AttendanceService attendance;
    private final RoomEventHub hub;
    private final CameraFeedRepository feeds;
    private final AttendanceRepository sessions;

    public InternalController(@Value("${app.internal.api-key:}") String key, RoomRepository rooms,
                              AttendanceService attendance, RoomEventHub hub, CameraFeedRepository feeds,
                              AttendanceRepository sessions) {
        this.key = key.getBytes(StandardCharsets.UTF_8);
        this.rooms = rooms;
        this.attendance = attendance;
        this.hub = hub;
        this.feeds = feeds;
        this.sessions = sessions;
    }

    /** embedding ของนักศึกษา 1 คน (base64 ของ bytea ที่เข้ารหัสแล้ว · ได้ถึง 5 ชุด) */
    public record GalleryStudent(UUID studentId, List<String> embeddings) {}

    /**
     * ใบหน้าที่ต้องจับคู่ในห้องนี้ตอนนี้ · sessionId = null = ไม่มีคาบ (ตั้งแต่ check_in_opens ถึง end_time)
     * cameraEnabled = false = อาจารย์ปิดการเช็คชื่อด้วยกล้อง → students ว่าง
     */
    public record Gallery(UUID sessionId, boolean cameraEnabled, List<GalleryStudent> students) {}

    /** กล้องทั้งหมดที่ AI Service ต้องอ่าน · ภาพอยู่ที่ MediaMTX path = streamPath */
    @GetMapping("/cameras")
    public List<CameraFeed> cameras(@RequestHeader(value = "X-Internal-Key", required = false) String k) {
        requireKey(k);
        return feeds.activeCameras();
    }

    /** AI Service ดึงทุก ~30 วินาที · กติกาเดียวกับ recordSighting (ลงทะเบียน + consent granted + บัญชีเปิด) */
    @GetMapping("/rooms/{roomId}/gallery")
    public Gallery gallery(@RequestHeader(value = "X-Internal-Key", required = false) String k,
                           @PathVariable UUID roomId) {
        requireKey(k);
        if (!rooms.roomExists(roomId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "ไม่พบห้องเรียน");
        }
        var found = sessions.sessionInRoomAt(roomId, Times.now().withNano(0));
        if (found.isEmpty()) return new Gallery(null, false, List.of());
        var s = found.get();
        if (!s.cameraEnabled()) return new Gallery(s.sessionId(), false, List.of());

        Map<UUID, List<String>> byStudent = new LinkedHashMap<>();
        Base64.Encoder b64 = Base64.getEncoder();
        for (var row : feeds.embeddingsForSection(s.sectionId())) {
            byStudent.computeIfAbsent(row.studentId(), id -> new ArrayList<>()).add(b64.encodeToString(row.embedding()));
        }
        List<GalleryStudent> students = byStudent.entrySet().stream()
                .map(e -> new GalleryStudent(e.getKey(), e.getValue())).toList();
        return new Gallery(s.sessionId(), true, students);
    }

    /** heartbeat ของกล้อง · เปลี่ยนสถานะ -> ส่ง event camera ไปที่ห้อง */
    @PostMapping("/cameras/{cameraCode}/heartbeat")
    public CameraEvent heartbeat(@RequestHeader(value = "X-Internal-Key", required = false) String k,
                                 @PathVariable String cameraCode, @RequestBody CameraHeartbeat body) {
        requireKey(k);
        if (body == null || !CAMERA_STATUSES.contains(body.status())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "status ต้องเป็น live หรือ no_signal");
        }
        var camera = rooms.cameraByCode(cameraCode)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "ไม่พบกล้อง"));
        rooms.updateCameraStatus(camera.cameraId(), body.status());
        if (!body.status().equals(camera.status())) {
            hub.publish(camera.roomId(), "camera", new CameraEvent(body.status()));
        }
        return new CameraEvent(body.status());
    }

    /** จำนวนใบหน้าในเฟรม -> event faces (R1 ไม่เก็บลง DB · R2 จะเก็บใน occupancy_snapshots) */
    @PostMapping("/rooms/{roomId}/faces")
    public FacesEvent faces(@RequestHeader(value = "X-Internal-Key", required = false) String k,
                            @PathVariable UUID roomId, @RequestBody FacesReport body) {
        requireKey(k);
        if (body == null || body.detected() == null || body.detected() < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "detected ต้องเป็นจำนวนเต็ม >= 0");
        }
        if (!rooms.roomExists(roomId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "ไม่พบห้องเรียน");
        }
        var event = new FacesEvent(body.detected(), body.at() == null ? Times.now().withNano(0) : body.at());
        hub.publish(roomId, "faces", event);
        return event;
    }

    /** กล้องจับคู่ใบหน้าได้ -> เช็คชื่อตามกติกา (ดู AttendanceService.recordSighting) */
    @PostMapping("/rooms/{roomId}/sightings")
    public SightingResult sighting(@RequestHeader(value = "X-Internal-Key", required = false) String k,
                                   @PathVariable UUID roomId, @RequestBody Sighting body) {
        requireKey(k);
        if (!rooms.roomExists(roomId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "ไม่พบห้องเรียน");
        }
        return attendance.recordSighting(roomId, body);
    }

    private void requireKey(String given) {
        if (key.length == 0) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "ยังไม่ได้ตั้ง INTERNAL_API_KEY");
        }
        if (given == null || !MessageDigest.isEqual(key, given.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid internal key");
        }
    }
}
