package com.faculty.attendance.attendance;

import com.faculty.attendance.common.ImportValidationException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.web.multipart.MultipartFile;

/**
 * อ่านไฟล์นำเข้าผลเช็คชื่อใน memory (ไม่เขียนลง disk) · รับเฉพาะ CSV UTF-8 (มี / ไม่มี BOM) คั่นด้วย comma
 * หรือ XLSX (ชีตแรก) · หัวตารางต้องอยู่แถวแรก · ไม่เดา encoding / ตัวคั่น
 * ค่าในเซลล์ XLSX = ข้อความที่ Excel แสดง (DataFormatter, Locale.US) เพื่อให้ตรงกับที่ผู้ใช้เห็นตอนเลือกรูปแบบเวลา
 */
public final class ImportFileReader {
    private ImportFileReader() {}

    public static final long MAX_BYTES = 2L * 1024 * 1024;
    public static final int MAX_ROWS = 500;

    /** แถวข้อมูล 1 แถว · rowNo = เลขแถวในไฟล์ (หัวตาราง = 1) · cells ยาวเท่า headers */
    public record DataRow(int rowNo, List<String> cells) {}

    public record ParsedFile(String fileName, String sha256, List<String> headers, List<DataRow> rows) {}

    public static ParsedFile read(MultipartFile file) {
        return read(file, false);
    }

    /** allowTxt = รับ .txt เป็น CSV (UTF-8 คั่นด้วย comma เหมือนกัน) · ใช้กับนำเข้ารายชื่อนักศึกษา */
    public static ParsedFile read(MultipartFile file, boolean allowTxt) {
        if (file == null || file.isEmpty()) throw ImportValidationException.file("กรุณาเลือกไฟล์");
        if (file.getSize() > MAX_BYTES) throw ImportValidationException.file("ไฟล์ใหญ่เกิน 2 MB");
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().trim();
        String lower = name.toLowerCase(Locale.ROOT);
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw ImportValidationException.file("อ่านไฟล์ไม่ได้");
        }
        List<List<String>> table;
        if (lower.endsWith(".csv") || (allowTxt && lower.endsWith(".txt"))) {
            table = csv(bytes);
        } else if (lower.endsWith(".xlsx")) {
            table = xlsx(bytes);
        } else {
            throw ImportValidationException.file(allowTxt ? "รองรับเฉพาะไฟล์ .csv / .txt (UTF-8 คั่นด้วย comma) หรือ .xlsx"
                    : "รองรับเฉพาะไฟล์ .csv (UTF-8) หรือ .xlsx");
        }
        return toParsed(name, sha256(bytes), table);
    }

    // ---------- CSV ----------

    private static List<List<String>> csv(byte[] bytes) {
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw ImportValidationException.file("ไฟล์ CSV ต้องเข้ารหัสเป็น UTF-8 (บันทึกใหม่เป็น \"CSV UTF-8\")");
        }
        if (text.startsWith("﻿")) text = text.substring(1);
        List<List<String>> out = new ArrayList<>();
        CSVFormat format = CSVFormat.DEFAULT.builder().setDelimiter(',').setQuote('"').get();
        try (CSVParser parser = CSVParser.parse(new StringReader(text), format)) {
            for (CSVRecord r : parser) {
                List<String> cells = new ArrayList<>(r.size());
                for (String v : r) cells.add(v);
                out.add(cells);
            }
        } catch (IOException | IllegalStateException | java.io.UncheckedIOException e) {
            throw ImportValidationException.file("รูปแบบ CSV ไม่ถูกต้อง (เครื่องหมายคำพูดไม่ครบคู่)");
        }
        if (!out.isEmpty() && out.getFirst().size() == 1) {
            String h = out.getFirst().getFirst();
            if (h.contains(";") || h.contains("\t")) {
                throw ImportValidationException.file("ไฟล์ CSV ต้องคั่นด้วย comma (,) ไม่ใช่ ; หรือ tab");
            }
        }
        return out;
    }

    // ---------- XLSX ----------

    private static List<List<String>> xlsx(byte[] bytes) {
        if (bytes.length < 4 || bytes[0] != 'P' || bytes[1] != 'K') {
            throw ImportValidationException.file("ไฟล์ .xlsx ไม่ถูกต้อง (ไม่ใช่ไฟล์ Excel)");
        }
        List<List<String>> out = new ArrayList<>();
        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            if (wb.getNumberOfSheets() == 0) throw ImportValidationException.file("ไฟล์ Excel ไม่มีชีต");
            Sheet sheet = wb.getSheetAt(0);
            DataFormatter fmt = new DataFormatter(Locale.US);
            FormulaEvaluator eval = wb.getCreationHelper().createFormulaEvaluator();
            int last = sheet.getLastRowNum();
            if (last > MAX_ROWS + 1000) throw ImportValidationException.file("ไฟล์มีข้อมูลเกิน " + MAX_ROWS + " แถว");
            for (int i = 0; i <= last; i++) {
                Row row = sheet.getRow(i);
                List<String> cells = new ArrayList<>();
                if (row != null) {
                    int lastCell = Math.max(row.getLastCellNum(), 0);
                    for (int c = 0; c < lastCell; c++) {
                        Cell cell = row.getCell(c);
                        cells.add(cell == null ? "" : fmt.formatCellValue(cell, eval));
                    }
                }
                out.add(cells);
            }
        } catch (ImportValidationException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw ImportValidationException.file("เปิดไฟล์ Excel ไม่ได้ (ไฟล์เสียหรือมีรหัสผ่าน)");
        }
        return out;
    }

    // ---------- common ----------

    private static ParsedFile toParsed(String name, String sha, List<List<String>> table) {
        if (table.isEmpty() || isBlank(table.getFirst())) {
            throw ImportValidationException.file("แถวแรกของไฟล์ต้องเป็นหัวตาราง");
        }
        List<String> headers = new ArrayList<>();
        for (String h : table.getFirst()) headers.add(h == null ? "" : h.trim());
        while (!headers.isEmpty() && headers.getLast().isEmpty()) headers.removeLast();
        List<ImportValidationException.RowError> errors = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < headers.size(); i++) {
            String h = headers.get(i);
            if (h.isEmpty()) {
                errors.add(new ImportValidationException.RowError(1, null, null,
                        "หัวตารางคอลัมน์ที่ " + (i + 1) + " ว่าง"));
            } else if (!seen.add(h)) {
                errors.add(new ImportValidationException.RowError(1, h, h, "หัวตารางซ้ำกัน: " + h));
            }
        }
        if (!errors.isEmpty()) throw new ImportValidationException("หัวตารางไม่ถูกต้อง", errors);

        List<DataRow> rows = new ArrayList<>();
        for (int i = 1; i < table.size(); i++) {
            List<String> raw = table.get(i);
            if (isBlank(raw)) continue;      // แถวว่างทั้งแถว (มักอยู่ท้ายไฟล์) ไม่นับ
            List<String> cells = new ArrayList<>(headers.size());
            for (int c = 0; c < headers.size(); c++) {
                cells.add(c < raw.size() && raw.get(c) != null ? raw.get(c).trim() : "");
            }
            rows.add(new DataRow(i + 1, cells));
            if (rows.size() > MAX_ROWS) throw ImportValidationException.file("ไฟล์มีข้อมูลเกิน " + MAX_ROWS + " แถว");
        }
        if (rows.isEmpty()) throw ImportValidationException.file("ไฟล์ไม่มีข้อมูล (มีแต่หัวตาราง)");
        return new ParsedFile(name, sha, List.copyOf(headers), rows);
    }

    private static boolean isBlank(List<String> cells) {
        for (String c : cells) if (c != null && !c.isBlank()) return false;
        return true;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
