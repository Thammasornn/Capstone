package com.faculty.attendance.common;

import java.util.List;

/**
 * ไฟล์นำเข้าผลเช็คชื่อมีข้อผิดพลาด → 400 พร้อมรายการ error ทุกแถว (ApiErrors)
 * rowNo = เลขแถวในไฟล์ (หัวตาราง = 1) · column / value อาจเป็น null เมื่อเป็นปัญหาทั้งไฟล์
 */
public class ImportValidationException extends RuntimeException {

    public record RowError(Integer rowNo, String column, String value, String message) {}

    private final List<RowError> errors;

    public ImportValidationException(String message, List<RowError> errors) {
        super(message);
        this.errors = List.copyOf(errors);
    }

    /** ปัญหาทั้งไฟล์ (ไม่ใช่รายแถว) */
    public static ImportValidationException file(String message) {
        return new ImportValidationException(message, List.of(new RowError(null, null, null, message)));
    }

    public List<RowError> errors() {
        return errors;
    }
}
