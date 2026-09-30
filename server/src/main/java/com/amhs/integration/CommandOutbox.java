package com.amhs.integration;
import java.util.List;
/**
 * Device commands recorded with the state change that caused them.
 *
 * <p>{@link #enqueue} must run inside the caller's transaction so a rolled back state change
 * cannot leave a command behind, and a committed state change cannot lose one.
 */
public interface CommandOutbox {
    /** Returns the existing row when the command id was already written, so a retried caller is safe. */
    DeviceCommand enqueue(long orderId, String commandId, String commandType, String payload);
    /** Marks rows DISPATCHING and hands them over. Concurrent workers never claim the same row. */
    List<DeviceCommand> claim(int limit, int maxAttempts);
    /** Records what the channel reported for a claimed command. */
    DeviceCommand settle(String commandId, DeviceChannel.Verdict verdict, java.time.Duration retryDelay);
    /** The device acknowledged receipt. Receipt is not execution completion. */
    DeviceCommand acknowledge(String commandId);
    List<DeviceCommand> byOrder(long siteId, long orderId);
    /** Commands of this type that the device has not acknowledged yet. */
    List<DeviceCommand> unresolved(long siteId, long orderId, String commandType);
    /** Commands nobody can settle automatically, oldest first. */
    List<DeviceCommand> inDoubt(long siteId, int limit);
}
