package com.amhs.integration;
/**
 * Transport to one device family. Implementations must distinguish "provably not delivered"
 * from "unknown": the difference decides whether the command may be sent again at all.
 */
public interface DeviceChannel {
    enum Outcome {
        /** The link confirmed delivery. Not a statement about execution. */
        DELIVERED,
        /** Nothing left this system: no link, refused, or rejected before any byte was written. */
        NOT_DELIVERED,
        /** Written but unconfirmed, or the answer never came. Must not be repeated. */
        IN_DOUBT
    }
    record Verdict(Outcome outcome, String detail) {
        public static Verdict delivered() { return new Verdict(Outcome.DELIVERED,null); }
        public static Verdict notDelivered(String detail) { return new Verdict(Outcome.NOT_DELIVERED,detail); }
        public static Verdict inDoubt(String detail) { return new Verdict(Outcome.IN_DOUBT,detail); }
    }
    /** Never throws for an ordinary link problem; a thrown exception is treated as IN_DOUBT. */
    Verdict send(DeviceCommand command);
}
