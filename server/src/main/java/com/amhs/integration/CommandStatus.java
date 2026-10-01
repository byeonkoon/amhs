package com.amhs.integration;
/** Lifecycle of one device command. Reaching the device is not the same as being obeyed. */
public enum CommandStatus {
    /** Written with the state change it belongs to; nothing has left this system yet. */
    PENDING,
    /** Claimed for sending. A row left here means the send was interrupted and its outcome is unknown. */
    DISPATCHING,
    /** On the wire. The device may already be acting on it, so it is never sent again. */
    SENT,
    /** The device acknowledged receipt. This is receipt, not execution completion. */
    ACCEPTED,
    /** The send provably did not reach the device, so repeating it cannot duplicate anything. */
    FAILED,
    /** Never left, and its order stopped moving before it could. It is kept as a record and never sent. */
    WITHDRAWN;
    /** Only outcomes that prove nothing reached the device may be repeated. */
    public boolean retryable() { return this==PENDING||this==FAILED; }
    /** An outcome nobody can settle automatically; an operator has to decide. */
    public boolean inDoubt() { return this==DISPATCHING||this==SENT; }
}
