package com.faculty.attendance.common;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * UNIQUE ใน DB ชนตอนเขียนพร้อมกัน (ผ่านการตรวจล่วงหน้ามาแล้ว) -> 409 แทน 500
 * sendError ให้ได้ JSON รูปแบบเดียวกับ ResponseStatusException (server.error.include-message)
 */
@RestControllerAdvice
public class ApiErrors {

    @ExceptionHandler(DuplicateKeyException.class)
    public void duplicate(HttpServletResponse response) throws IOException {
        response.sendError(409, "ข้อมูลซ้ำกับที่มีอยู่แล้ว กรุณาโหลดหน้าใหม่แล้วลองอีกครั้ง");
    }

    /** นำเข้าผลเช็คชื่อ: รูปแบบเดียวกับ error เดิม + errors (รายแถว) · ใช้เฉพาะ ImportValidationException */
    @ExceptionHandler(ImportValidationException.class)
    public ResponseEntity<Map<String, Object>> importInvalid(ImportValidationException e, HttpServletRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", 400);
        body.put("error", "Bad Request");
        body.put("message", e.getMessage());
        body.put("path", request.getRequestURI());
        body.put("errors", e.errors());
        return ResponseEntity.badRequest().body(body);
    }
}
