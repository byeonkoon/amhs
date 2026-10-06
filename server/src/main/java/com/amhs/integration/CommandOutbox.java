package com.amhs.integration;
import java.util.List;
/**
 * Device commands recorded with the state change that caused them.
 *
 * <p>{@link #enqueue} must run inside the caller's transaction so a rolled back state change
 * cannot leave a command behind, and a committed state change cannot lose one.
 */
public interface CommandOutbox {
    /**
     * Returns the existing row when the command id was already written, so a retried caller is safe.
     * {@code vehicleId} is the vehicle the command moves; commands for one vehicle leave in the order
     * they were written.
     */
    DeviceCommand enqueue(long orderId, long vehicleId, String commandId, String commandType, String payload);
    /**
     * A command that belongs to no transport order: it moves the vehicle for the site's own reasons.
     * Same transaction rule and same per-vehicle ordering as {@link #enqueue}.
     */
    DeviceCommand enqueueForVehicle(long siteId, long vehicleId, String commandId, String commandType, String payload);
    /**
     * Marks this site's rows DISPATCHING and hands them over, oldest first. Concurrent workers never
     * claim the same row, and a command is not claimed while an earlier command for the same vehicle
     * is still waiting to leave, waiting to be retried, or out with an unknown outcome.
     */
    List<DeviceCommand> claim(long siteId, int limit, int maxAttempts);
    /**
     * Takes back every command of the order that has not left, under the same row locks the publisher
     * claims with, so nothing can be claimed between this decision and the caller's. Must run inside
     * the caller's transaction.
     *
     * @return how many were withdrawn, and whether any command of the order has already left or may
     *         have (claimed, sent or acknowledged), in which case the vehicle may be acting on it
     */
    Withdrawal withdraw(long orderId, String reason);
    record Withdrawal(int withdrawn, boolean anyLeft) {}
    /**
     * What taking back a vehicle's order-less commands found. {@code inFlight}: one is being sent or
     * was sent without an answer yet. {@code delivered}: one written since the given time is in
     * flight or was accepted, so the vehicle may be acting on it.
     */
    record VehicleWithdrawal(int withdrawn, boolean inFlight, boolean delivered) {}
    /**
     * The same for a vehicle's commands that belong to no order (moves out of a zone, an operator's
     * moves): what never left is withdrawn, what is being sent is marked, and what may have reached
     * the vehicle since {@code since} is reported.
     */
    VehicleWithdrawal withdrawForVehicle(long siteId, long vehicleId, String reason, java.time.OffsetDateTime since);

    /**
     * Takes back every movement the vehicle has not been sent, whether or not it belongs to an
     * order, and marks the one being sent so a failed send ends WITHDRAWN rather than FAILED.
     *
     * <p>Commands for one vehicle leave in the order they were written, so a movement left FAILED
     * holds back everything behind it — including the stop that was meant to settle the vehicle
     * down. An order's movement is as much in the way as an order-less one, so both are covered
     * here. Only movements are: a transfer the order is part-way through is a different decision
     * and is not taken back on the strength of a vehicle being told to stop.
     */
    VehicleWithdrawal withdrawMovementForVehicle(long siteId, long vehicleId, String reason);
    /** Withdraws one command if it has not left yet (pending, or failed and waiting to be tried again). */
    void withdrawIfUnsent(String commandId, String reason);
    /**
     * An operator closes a command whose outcome was lost in a restart (claimed or sent, never
     * answered), on the evidence of the vehicle standing still since. It is recorded as possibly
     * received -- ACCEPTED with the outcome {@code CLOSED_AFTER_RESTART} and the operator's reason --
     * never as "did not arrive", which nobody can know. False when it was not in doubt any more.
     */
    boolean closeInDoubtAfterRestart(String commandId, String reason);
    /**
     * Records what the channel reported for a claimed command. A result never moves a command back:
     * after an acknowledgement it is only recorded, and a command whose withdrawal was asked for
     * during the send is withdrawn if the send failed.
     */
    DeviceCommand settle(String commandId, DeviceChannel.Verdict verdict, java.time.Duration retryDelay);
    /** The device acknowledged receipt. Receipt is not execution completion. */
    DeviceCommand acknowledge(String commandId);
    List<DeviceCommand> byOrder(long siteId, long orderId);
    /** Commands of this type that the device has not acknowledged yet. */
    List<DeviceCommand> unresolved(long siteId, long orderId, String commandType);
    /** Every command for this vehicle, with or without an order, that is not settled yet. */
    List<DeviceCommand> unresolvedForVehicle(long siteId, long vehicleId);
    /** Commands nobody can settle automatically, oldest first. */
    List<DeviceCommand> inDoubt(long siteId, int limit);
}
