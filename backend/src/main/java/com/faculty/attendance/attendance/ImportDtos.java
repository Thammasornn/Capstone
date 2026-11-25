package com.faculty.attendance.attendance;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** นำเข้าผลเช็คชื่อจากไฟล์ (AttendanceImportController) · เวลาเป็นเวลาไทย */
public final class ImportDtos {
    private ImportDtos() {}

    /**
     * mode = mapped · identifierType = email | student_code · timeFormat ตาม AttendanceImportService.TIME_FORMATS ·
     * statusValues = ค่าในไฟล์ → on_time | late | absent · defaultStatus = on_time | late
     */
    public record Mapping(String identifierColumn, String identifierType, String joinTimeColumn, String timeFormat,
                          String statusColumn, Map<String, String> statusValues, String defaultStatus) {}

    /** POST .../import/inspect · sampleRows = 5 แถวแรก · distinctValues เฉพาะคอลัมน์ที่มีค่าไม่ซ้ำ ≤ 20 ค่า */
    public record InspectResponse(List<String> headers, List<List<String>> sampleRows,
                                  Map<String, List<String>> distinctValues, int rowCount) {}

    public record SessionWindow(String startTime, String onTimeUntil, String lateUntil, String endTime) {}

    public record DuplicateOf(UUID importId, LocalDateTime importedAt, String importedBy, String fileName) {}

    /** action = insert | replace | skip | conflict · applied = เขียนลง attendance_records แล้วจริง (dryRun = false เสมอ) */
    public record ImportRowResult(int rowNo, UUID studentId, String studentCode, String studentName,
                                  String fromSource, String fromStatus, String toStatus, LocalDateTime checkInTime,
                                  String action, boolean applied) {}

    /** reason = not_found | not_enrolled | duplicate */
    public record Unmatched(int rowNo, String value, String reason) {}

    public record ImportSummary(int totalRows, int insert, int replace, int skip, int conflict, int unmatched,
                                int applied) {}

    public record ImportResponse(boolean dryRun, String mode, UUID importId, DuplicateOf duplicateOf,
                                 SessionWindow sessionWindow, List<ImportRowResult> rows, List<Unmatched> unmatched,
                                 ImportSummary summary) {}

    /** GET .../imports */
    public record HistoryItem(UUID importId, LocalDateTime importedAt, UUID importedById, String importedBy,
                              String mode, String method, String fileName, String reason, boolean overrideCamera,
                              boolean overrideManual, int totalRows, int insert, int replace, int skip,
                              int conflict, int unmatched) {}

    /** แถวของไฟล์ที่เก็บไว้ (ค่าดิบ + ผลที่คำนวณ + บันทึกจริงไหม) */
    public record StoredRow(int rowNo, String rawIdentifier, String rawJoinTime, String rawStatus, UUID studentId,
                            String studentCode, String studentName, String status, LocalDateTime checkInTime,
                            String action, String unmatchedReason, boolean applied) {}

    /** GET .../imports/{importId} */
    public record ImportDetail(HistoryItem summary, JsonNode mapping, List<StoredRow> rows) {}
}
