package com.faculty.attendance.attendance;

import com.faculty.attendance.attendance.AttendanceImportRepository.EnrolledStudent;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataValidation;
import org.apache.poi.ss.usermodel.DataValidationHelper;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/** ไฟล์ template สำหรับนำเข้าผลเช็คชื่อ (คอลัมน์ตายตัว: AttendanceImportService.TEMPLATE_HEADERS) */
final class ImportTemplates {
    private ImportTemplates() {}

    private static final List<String> GUIDE = List.of(
            "วิธีกรอก template นำเข้าผลเช็คชื่อ",
            "",
            "ห้ามแก้ชื่อหรือลำดับหัวตาราง (student_code, student_name, status, check_in_time) และใช้ชีตแรกเท่านั้น",
            "status: on_time = ตรงเวลา · late = สาย · absent = ขาด",
            "status ว่าง = ไม่เปลี่ยนผลเช็คชื่อของนักศึกษาคนนั้น",
            "check_in_time: เวลาเข้าเรียนรูปแบบ HH:mm เช่น 13:05 (ไม่ใส่ = ใช้เวลาเริ่มคาบ)",
            "absent ไม่ต้องใส่ check_in_time",
            "ผลที่อาจารย์แก้เอง หรือกล้องบันทึกไว้แล้วต่างจากไฟล์ จะแสดงเป็น conflict ให้เลือกก่อนบันทึก");

    static byte[] xlsx(List<String> headers, Collection<EnrolledStudent> students) {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("attendance");
            CellStyle text = wb.createCellStyle();
            text.setDataFormat(wb.createDataFormat().getFormat("@"));   // ข้อความ: รหัส / เวลาไม่ถูก Excel แปลง
            CellStyle head = wb.createCellStyle();
            Font bold = wb.createFont();
            bold.setBold(true);
            head.setFont(bold);

            Row h = sheet.createRow(0);
            for (int c = 0; c < headers.size(); c++) {
                h.createCell(c).setCellValue(headers.get(c));
                h.getCell(c).setCellStyle(head);
                sheet.setDefaultColumnStyle(c, text);
            }
            int r = 1;
            for (EnrolledStudent s : students) {
                Row row = sheet.createRow(r++);
                for (int c = 0; c < headers.size(); c++) row.createCell(c).setCellStyle(text);
                row.getCell(0).setCellValue(s.studentCode());
                row.getCell(1).setCellValue(s.fullName());
            }
            int lastRow = Math.max(r - 1, 1);
            DataValidationHelper dv = sheet.getDataValidationHelper();
            DataValidation v = dv.createValidation(dv.createExplicitListConstraint(new String[] {"on_time", "late", "absent"}),
                    new CellRangeAddressList(1, lastRow, 2, 2));
            v.setShowErrorBox(true);
            v.createErrorBox("status ไม่ถูกต้อง", "เลือก on_time, late หรือ absent (หรือเว้นว่าง = ไม่เปลี่ยน)");
            v.setEmptyCellAllowed(true);
            sheet.addValidationData(v);
            sheet.setColumnWidth(0, 16 * 256);
            sheet.setColumnWidth(1, 30 * 256);
            sheet.setColumnWidth(2, 12 * 256);
            sheet.setColumnWidth(3, 15 * 256);
            sheet.createFreezePane(0, 1);

            Sheet guide = wb.createSheet("วิธีกรอก");
            for (int i = 0; i < GUIDE.size(); i++) guide.createRow(i).createCell(0).setCellValue(GUIDE.get(i));
            guide.setColumnWidth(0, 100 * 256);

            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** CSV UTF-8 มี BOM (Excel เปิดภาษาไทยถูก) · คั่นด้วย comma */
    static byte[] csv(List<String> headers, Collection<EnrolledStudent> students) {
        StringBuilder sb = new StringBuilder("﻿");
        try (CSVPrinter p = new CSVPrinter(sb, CSVFormat.DEFAULT)) {
            p.printRecord(headers);
            for (EnrolledStudent s : students) p.printRecord(s.studentCode(), s.fullName(), "", "");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }
}
