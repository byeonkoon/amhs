package com.amhs.integration;
import java.time.OffsetDateTime;
/**
 * One command row. The payload is opaque here; the channel adapter owns its encoding.
 * {@code orderId} is null for a movement that belongs to no transport order, such as moving a
 * vehicle out of a zone after its work there is done.
 */
public record DeviceCommand(long id, Long orderId, String commandId, String commandType, String payload,
                            CommandStatus status, int attempt, String lastOutcome, String lastError,
                            OffsetDateTime createdAt, OffsetDateTime publishedAt, OffsetDateTime settledAt,
                            Long vehicleId) {}
