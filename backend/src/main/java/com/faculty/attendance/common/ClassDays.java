package com.faculty.attendance.common;

import java.util.ArrayList;
import java.util.List;

/** API ใช้ MON–SUN · DB (section_class_days.day_of_week) ใช้ ISO 1 = จันทร์ … 7 = อาทิตย์ */
public final class ClassDays {
    private ClassDays() {}

    private static final List<String> DAYS = List.of("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN");

    /** "MON" -> 1 · ไม่รู้จัก = -1 */
    public static int toIso(String day) {
        if (day == null) return -1;
        int i = DAYS.indexOf(day.trim().toUpperCase());
        return i < 0 ? -1 : i + 1;
    }

    public static List<String> fromIso(Object[] isoDays) {
        List<String> out = new ArrayList<>();
        if (isoDays == null) return out;
        for (Object d : isoDays) {
            int n = ((Number) d).intValue();
            if (n >= 1 && n <= 7) out.add(DAYS.get(n - 1));
        }
        return out;
    }
}
