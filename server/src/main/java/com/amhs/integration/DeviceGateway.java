package com.amhs.integration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.time.Duration;
/**
 * Publishes recorded commands after the transaction that produced them has committed.
 *
 * <p>Deliberately not transactional: claiming, sending and settling are three steps so that an
 * interrupted send stays visible instead of rolling back into the queue and being sent twice.
 */
@Service
public class DeviceGateway {
    private static final Logger log=LoggerFactory.getLogger(DeviceGateway.class);
    private final CommandOutbox outbox; private final DeviceChannel channel;
    private final int batchSize; private final int maxAttempts; private final Duration retryDelay;
    public DeviceGateway(CommandOutbox outbox,DeviceChannel channel,
                         @Value("${amhs.device.batch-size:32}") int batchSize,
                         @Value("${amhs.device.max-attempts:5}") int maxAttempts,
                         @Value("${amhs.device.retry-delay-seconds:15}") long retryDelaySeconds) {
        this.outbox=outbox; this.channel=channel; this.batchSize=batchSize; this.maxAttempts=maxAttempts;
        this.retryDelay=Duration.ofSeconds(retryDelaySeconds);
    }
    public record Publication(int claimed, int delivered, int notDelivered, int inDoubt) {}
    public Publication publishPending(long siteId) {
        var claimed=outbox.claim(siteId,batchSize,maxAttempts);
        int delivered=0,notDelivered=0,inDoubt=0;
        for (var command : claimed) {
            DeviceChannel.Verdict verdict;
            try {
                verdict=channel.send(command);
                // A channel that answers null tells us nothing, which is doubt, not success.
                if (verdict==null) verdict=DeviceChannel.Verdict.inDoubt("CHANNEL_RETURNED_NO_VERDICT");
            } catch (RuntimeException e) {
                // The command may have reached the device before the failure, so it is never
                // repeated on the strength of an exception alone.
                verdict=DeviceChannel.Verdict.inDoubt(e.getClass().getSimpleName());
                log.warn("device send raised for command {}",command.commandId(),e);
            }
            switch (verdict.outcome()) {
                case DELIVERED -> delivered++;
                case NOT_DELIVERED -> notDelivered++;
                case IN_DOUBT -> inDoubt++;
            }
            outbox.settle(command.commandId(),verdict,retryDelay);
        }
        return new Publication(claimed.size(),delivered,notDelivered,inDoubt);
    }
}
