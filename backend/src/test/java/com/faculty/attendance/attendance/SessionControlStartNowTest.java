package com.faculty.attendance.attendance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.faculty.attendance.attendance.SessionControlRepository.SectionTimes;
import com.faculty.attendance.attendance.SessionControlRepository.SessionRow;
import com.faculty.attendance.attendance.SessionControlService.ClassSessionInfo;
import com.faculty.attendance.attendance.SessionControlService.StartSessionRequest;
import com.faculty.attendance.audit.AuditLogRepository;
import com.faculty.attendance.auth.AccessService;
import com.faculty.attendance.auth.CurrentUser;
import com.faculty.attendance.common.Times;
import com.faculty.attendance.room.RoomEventHub;
import com.faculty.attendance.room.RoomRepository;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/** startNow: เปิดคาบที่จบแล้วอีกครั้ง (ลบเฉพาะขาดของระบบ) / ย้ายคาบที่กำลังเรียนมาห้องนี้ — repository เป็น mock */
class SessionControlStartNowTest {

    private final UUID section = UUID.randomUUID();
    private final UUID room = UUID.randomUUID();
    private final UUID otherRoom = UUID.randomUUID();
    private final UUID sessionId = UUID.randomUUID();

    private SessionControlRepository repo;
    private AuditLogRepository audit;
    private RoomEventHub hub;
    private SessionControlService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        repo = mock(SessionControlRepository.class);
        audit = mock(AuditLogRepository.class);
        hub = mock(RoomEventHub.class);
        RoomRepository rooms = mock(RoomRepository.class);
        AccessService access = mock(AccessService.class);
        TransactionTemplate tx = mock(TransactionTemplate.class);
        when(tx.execute(any())).thenAnswer(inv -> ((TransactionCallback<Object>) inv.getArgument(0)).doInTransaction(null));
        when(rooms.roomExists(room)).thenReturn(true);
        when(access.user()).thenReturn(new CurrentUser(UUID.randomUUID(), "lecturer", null, UUID.randomUUID()));
        when(repo.section(section)).thenReturn(Optional.of(new SectionTimes(section, LocalTime.of(13, 0),
                LocalTime.of(16, 0), LocalTime.of(13, 0), LocalTime.of(13, 30), 15, null)));
        when(repo.roomConflict(any(), any(), any(), any(), any())).thenReturn(Optional.empty());
        when(repo.roomCode(otherRoom)).thenReturn(Optional.of("4301"));
        service = new SessionControlService(repo, mock(AttendanceRepository.class), rooms, access, audit, hub, tx,
                mock(ClassSessionRepository.class));
    }

    private SessionRow today(String status, LocalTime start, LocalTime end, UUID inRoom) {
        LocalDate d = Times.now().toLocalDate();
        return new SessionRow(sessionId, section, inRoom, d, start, end, start, start, start, start, status);
    }

    @Test
    void completedSessionIsReopenedAndOnlySystemAbsentsAreRemoved() {   // ข้อ 1
        when(repo.sessionOn(eq(section), any())).thenReturn(Optional.of(
                today("completed", LocalTime.of(0, 38), LocalTime.of(2, 33), room)));
        when(repo.deleteSystemAbsents(sessionId)).thenReturn(12);

        ClassSessionInfo info = service.startNow(room, new StartSessionRequest(section), "127.0.0.1");

        assertThat(info.startMode()).isEqualTo("reopened");
        assertThat(info.previousRoomCode()).isNull();
        assertThat(info.sessionStatus()).isEqualTo("in_progress");
        verify(repo).deleteSystemAbsents(sessionId);                // ลบเฉพาะ source = system (SQL ใน repository)
        verify(repo).moveToNow(eq(sessionId), eq(room), any());
        verify(audit).log(any(), eq("session.reopen"), eq("class_session"), eq(sessionId), anyString(),
                anyString(), any());
    }

    @Test
    void pastEndInProgressIsReopened() {   // ข้อ 4: job ยังไม่เปลี่ยนเป็น completed
        when(repo.sessionOn(eq(section), any())).thenReturn(Optional.of(
                today("in_progress", LocalTime.MIN, LocalTime.MIN.plusMinutes(1), room)));

        ClassSessionInfo info = service.startNow(room, new StartSessionRequest(section), "127.0.0.1");

        assertThat(info.startMode()).isEqualTo("reopened");
        verify(repo).deleteSystemAbsents(sessionId);
    }

    @Test
    void runningSessionInAnotherRoomIsMovedWithAllResultsKept() {   // ข้อ 3
        when(repo.sessionOn(eq(section), any())).thenReturn(Optional.of(
                today("in_progress", LocalTime.MIN, LocalTime.of(23, 59), otherRoom)));

        ClassSessionInfo info = service.startNow(room, new StartSessionRequest(section), "127.0.0.1");

        assertThat(info.startMode()).isEqualTo("moved");
        assertThat(info.previousRoomCode()).isEqualTo("4301");
        verify(repo, never()).deleteSystemAbsents(any());
        verify(repo).moveToNow(eq(sessionId), eq(room), any());
        verify(hub).publish(eq(room), eq("session"), any());
        verify(hub).publish(eq(otherRoom), eq("session"), any());
        verify(audit).log(any(), eq("session.move_room"), any(), any(), anyString(), anyString(), any());
    }

    @Test
    void noSessionTodayIsCreated() {
        when(repo.sessionOn(eq(section), any())).thenReturn(Optional.empty());
        when(repo.insertInProgress(eq(section), eq(room), any(), any())).thenReturn(sessionId);

        ClassSessionInfo info = service.startNow(room, new StartSessionRequest(section), "127.0.0.1");

        assertThat(info.startMode()).isEqualTo("created");
        verify(audit).log(any(), eq("session.start_now"), any(), any(), isNull(), anyString(), any());
    }
}
