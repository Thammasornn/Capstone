package com.faculty.attendance.room;

import com.faculty.attendance.auth.AccessService;
import com.faculty.attendance.common.Times;
import com.faculty.attendance.room.RoomDtos.*;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
public class RoomService {

    private final RoomRepository repo;
    private final AccessService access;
    private final RoomEventHub hub;
    private final String mediaBaseUrl;
    private final long streamTtlMinutes;

    public RoomService(RoomRepository repo, AccessService access, RoomEventHub hub,
                       @Value("${app.media.base-url}") String mediaBaseUrl,
                       @Value("${app.media.stream-ttl-minutes:90}") long streamTtlMinutes) {
        this.repo = repo;
        this.access = access;
        this.hub = hub;
        this.mediaBaseUrl = mediaBaseUrl.replaceAll("/+$", "");
        this.streamTtlMinutes = streamTtlMinutes;
    }

    /** B1 · lecturer เห็นเฉพาะห้องที่มีกลุ่มเรียน/คาบของตน */
    public List<Room> rooms() {
        return repo.list(access.requireStaff());
    }

    /** B4 */
    public Room room(UUID roomId) {
        return requireRoom(roomId);
    }

    /** B2 · URL ของ MediaMTX (WHEP) · RTSP credentials อยู่ฝั่ง MediaMTX ไม่ส่งให้เบราว์เซอร์ */
    public StreamInfo stream(UUID roomId) {
        requireRoom(roomId);
        var camera = repo.cameraOfRoom(roomId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "ห้องนี้ไม่มีกล้อง"));
        String path = camera.streamPath().replaceAll("^/+|/+$", "");
        return new StreamInfo("whep", mediaBaseUrl + "/" + path + "/whep",
                Times.now().plusMinutes(streamTtlMinutes).withNano(0));
    }

    /** B3 · ส่ง camera ทันทีที่ต่อสำเร็จ ที่เหลือมาจาก RoomEventHub.publish */
    public SseEmitter events(UUID roomId) {
        Room room = requireRoom(roomId);
        SseEmitter emitter = hub.subscribe(roomId);
        hub.send(emitter, "camera", new CameraEvent(room.cameraStatus()));
        return emitter;
    }

    private Room requireRoom(UUID roomId) {
        UUID lecturerId = access.requireStaff();
        return repo.find(roomId, lecturerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "ไม่พบห้องเรียน"));
    }
}
