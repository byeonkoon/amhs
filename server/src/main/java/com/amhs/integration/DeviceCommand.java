package com.amhs.integration;
import java.time.OffsetDateTime;
/** One command row. The payload is opaque here; the channel adapter owns its encoding. */
public record DeviceCommand(long id, long orderId, String commandId, String commandType, String payload,
                            CommandStatus status, int attempt, String lastOutcome, String lastError,
                            OffsetDateTime createdAt, OffsetDateTime publishedAt, OffsetDateTime settledAt,
                            Long vehicleId) {}
