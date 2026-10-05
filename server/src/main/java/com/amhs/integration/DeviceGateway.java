package com.amhs.integration;
import com.amhs.platform.OperationOptionConfiguration;
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
    private final OperationOptionConfiguration options; private final int batchSize;
    public DeviceGateway(CommandOutbox outbox,DeviceChannel channel,OperationOptionConfiguration options,
                         @Value("${amhs.device.batch-size:32}") int batchSize) {
        this.outbox=outbox; this.channel=channel; this.options=options; this.batchSize=batchSize;
    }
    public record Publication(int claimed, int delivered, int notDelivered, int inDoubt) {
        /** Nothing was sent, because nothing was asked of the wire. */
        public static Publication nothing() { return new Publication(0,0,0,0); }
    }
    public Publication publishPending(long siteId) {
        // How hard to retry is an operating decision, so it is read from the site's options each
        // sweep rather than fixed at start-up: changing it must not require a restart.
        var option=options.get(siteId).settings();
        var retryDelay=Duration.ofSeconds(option.commandRetrySeconds());
        var claimed=outbox.claim(siteId,batchSize,option.commandMaxAttempts());
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
            try {
                outbox.settle(command.commandId(),verdict,retryDelay);
            } catch (IllegalStateException e) {
                // One command's result that cannot be recorded must not leave the rest of the batch
                // claimed and unsent-looking; it stays visible in its own state for an operator.
                log.warn("could not record send result for command {}: {}",command.commandId(),e.getMessage());
            }
        }
        return new Publication(claimed.size(),delivered,notDelivered,inDoubt);
    }
}
