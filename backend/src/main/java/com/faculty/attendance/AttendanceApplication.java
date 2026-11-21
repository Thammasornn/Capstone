package com.faculty.attendance;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/** @EnableScheduling: SSE keep-alive (RoomEventHub) และ job สถานะคาบ / absent (AttendanceJobs) */
@SpringBootApplication
@EnableScheduling
public class AttendanceApplication {
    public static void main(String[] args) {
        SpringApplication.run(AttendanceApplication.class, args);
    }
}
