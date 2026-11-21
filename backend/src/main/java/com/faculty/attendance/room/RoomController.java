package com.faculty.attendance.room;

import com.faculty.attendance.room.RoomDtos.*;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Room Monitor (B1–B4) · admin / lecturer เท่านั้น */
@RestController
@RequestMapping("/api/rooms")
public class RoomController {

    private final RoomService service;

    public RoomController(RoomService service) {
        this.service = service;
    }

    /** B1 */
    @GetMapping
    public List<Room> rooms() {
        return service.rooms();
    }

    /** B4 */
    @GetMapping("/{roomId}")
    public Room room(@PathVariable UUID roomId) {
        return service.room(roomId);
    }

    /** B2 */
    @GetMapping("/{roomId}/stream")
    public StreamInfo stream(@PathVariable UUID roomId) {
        return service.stream(roomId);
    }

    /**
     * B3 SSE · EventSource ใส่ header ไม่ได้ → ส่ง token ทาง ?access_token= (เปิดเฉพาะ path นี้ ดู SecurityConfig)
     * event: camera, faces, check_in, session
     */
    @GetMapping(value = "/{roomId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable UUID roomId) {
        return service.events(roomId);
    }
}
