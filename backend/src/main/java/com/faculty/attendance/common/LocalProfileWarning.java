package com.faculty.attendance.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** เตือนใน log ทุกครั้งที่รันด้วยโปรไฟล์ local (key ทดสอบจาก application-local.yml ที่อยู่ใน git) */
@Component
@Profile("local")
class LocalProfileWarning {

    private static final Logger log = LoggerFactory.getLogger(LocalProfileWarning.class);

    @EventListener(ApplicationReadyEvent.class)
    void warn() {
        log.warn("""

                ==================================================================
                 โปรไฟล์ local: ใช้ key ทดสอบจาก application-local.yml (อยู่ใน git)
                 ใช้กับข้อมูล mock เท่านั้น · บน server ต้องตั้ง SPRING_PROFILES_ACTIVE=prod
                ==================================================================""");
    }
}
