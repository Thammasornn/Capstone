package com.faculty.attendance.attendance;

import com.faculty.attendance.room.RoomDtos.SessionEvent;
import com.faculty.attendance.room.RoomEventHub;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * ทุก 1 นาที:
 *  1. เลื่อน session_status: scheduled -> in_progress -> completed ตามเวลาไทย (ส่ง event session ไปที่ห้อง)
 *  2. ปิดช่วงเช็คชื่อแล้ว -> INSERT absent (source = system) ให้คนที่ยังไม่มีแถว
 * รันซ้ำ / หลาย replica พร้อมกันได้ (UPDATE มีเงื่อนไข, INSERT ใช้ ON CONFLICT DO NOTHING)
 * ปิดได้ด้วย app.jobs.enabled=false (เช่นตอนทดสอบ)
 */
@Component
@ConditionalOnProperty(name = "app.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class AttendanceJobs {

    private static final Logger log = LoggerFactory.getLogger(AttendanceJobs.class);
    private static final int ABSENT_LOOKBACK_DAYS = 2;

    private final AttendanceRepository repo;
    private final RoomEventHub hub;

    public AttendanceJobs(AttendanceRepository repo, RoomEventHub hub) {
        this.repo = repo;
        this.hub = hub;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 10_000)
    public void run() {
        try {
            for (var c : repo.advanceSessionStatuses()) {
                hub.publish(c.roomId(), "session", new SessionEvent(c.sessionId(), c.sessionStatus()));
            }
            int absents = repo.insertSystemAbsents(ABSENT_LOOKBACK_DAYS);
            if (absents > 0) log.info("บันทึก absent (system) {} แถว", absents);
        } catch (RuntimeException e) {
            log.error("AttendanceJobs ล้มเหลว", e);
        }
    }
}
