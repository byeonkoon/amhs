package com.amhs.transport;
import java.time.OffsetDateTime;
public record OrderEventView(int seq,OrderStatus oldStatus,OrderStatus newStatus,String reason,String actorCode,OffsetDateTime occurredAt){}
