package com.faculty.attendance.face;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public final class FaceDtos {
    private FaceDtos() {}

    /** response จาก AI Service */
    public record EmbeddingItem(List<Float> embedding, double quality) {}

    public record EmbeddingResponse(
            String model,
            @JsonProperty("model_version") String modelVersion,
            List<EmbeddingItem> items) {}

    /** response ของ API เรา */
    public record RegisterResponse(UUID studentId, int savedCount, String model) {}

    public record StatusResponse(UUID studentId, boolean registered,
                                 LocalDateTime registeredAt, int activeFaces) {}
}
