package com.faculty.attendance.common;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/** เวลาไทยของระบบ (ไม่ขึ้นกับ timezone ของ JVM หรือ DB session) */
public final class Times {
    private Times() {}

    public static final ZoneId BANGKOK = ZoneId.of("Asia/Bangkok");

    /** SQL: เวลาไทยตอนนี้ แบบ timestamp ไม่มี timezone ใช้เทียบกับ session_date + time */
    public static final String NOW_TH = "(now() AT TIME ZONE 'Asia/Bangkok')";

    public static LocalDateTime now() {
        return LocalDateTime.now(BANGKOK);
    }

    public static LocalDateTime local(OffsetDateTime t) {
        return t == null ? null : t.atZoneSameInstant(BANGKOK).toLocalDateTime();
    }

    /** เวลาไทยไม่มี timezone -> timestamptz สำหรับเขียนลง DB */
    public static OffsetDateTime offset(LocalDateTime t) {
        return t == null ? null : t.atZone(BANGKOK).toOffsetDateTime();
    }
}
