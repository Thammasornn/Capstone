package com.faculty.attendance.common;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.dao.DuplicateKeyException;
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
}
