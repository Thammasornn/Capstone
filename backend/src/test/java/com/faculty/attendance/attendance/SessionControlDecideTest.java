package com.faculty.attendance.attendance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.faculty.attendance.attendance.SessionControlRepository.SessionRow;
import com.faculty.attendance.attendance.SessionControlService.StartMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

/** SessionControlService.decide — เริ่มคาบตอนนี้เมื่อวันนี้กลุ่มเรียนมีคาบอยู่แล้ว (ไม่ต้องใช้ DB) */
class SessionControlDecideTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 27);
    private static final LocalDateTime NOW = TODAY.atTime(23, 0);
    private static final UUID ROOM = UUID.randomUUID();
    private static final UUID OTHER_ROOM = UUID.randomUUID();

    private static SessionRow row(String status, String start, String end, UUID room) {
        LocalTime s = LocalTime.parse(start), e = LocalTime.parse(end);
        return new SessionRow(UUID.randomUUID(), UUID.randomUUID(), room, TODAY, s, e, s, s.plusMinutes(15),
                s.plusMinutes(30), s.plusMinutes(30), status);
    }

    @Test
    void completedTodayIsReopened() {   // ข้อ 1: คาบหลังเที่ยงคืน 00:38–02:33 จบแล้ว
        assertThat(SessionControlService.decide(row("completed", "00:38", "02:33", ROOM), ROOM, NOW))
                .isEqualTo(StartMode.REOPENED);
    }

    @Test
    void pastEndButJobNotRunYetIsReopened() {   // ข้อ 4: ใช้สถานะตามเวลาจริง
        assertThat(SessionControlService.decide(row("in_progress", "20:00", "22:59", ROOM), ROOM, NOW))
                .isEqualTo(StartMode.REOPENED);
        assertThat(SessionControlService.decide(row("scheduled", "13:00", "16:00", ROOM), ROOM, NOW))
                .isEqualTo(StartMode.REOPENED);
    }

    @Test
    void runningInAnotherRoomIsMoved() {   // ข้อ 3
        assertThat(SessionControlService.decide(row("in_progress", "22:00", "23:30", OTHER_ROOM), ROOM, NOW))
                .isEqualTo(StartMode.MOVED);
        assertThat(SessionControlService.decide(row("scheduled", "22:30", "23:30", OTHER_ROOM), ROOM, NOW))
                .isEqualTo(StartMode.MOVED);
    }

    @Test
    void runningInSameRoomIsConflict() {   // ข้อ 2
        assertThatThrownBy(() -> SessionControlService.decide(row("in_progress", "22:00", "23:30", ROOM), ROOM, NOW))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("กำลังเรียนอยู่แล้วในห้องนี้");
    }

    @Test
    void notStartedOrCancelledIsRescheduled() {   // ข้อ 5
        assertThat(SessionControlService.decide(row("scheduled", "23:30", "23:59", ROOM), ROOM, NOW))
                .isEqualTo(StartMode.RESCHEDULED);
        assertThat(SessionControlService.decide(row("cancelled", "13:00", "16:00", OTHER_ROOM), ROOM, NOW))
                .isEqualTo(StartMode.RESCHEDULED);
    }
}
