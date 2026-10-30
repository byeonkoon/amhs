package com.amhs.integration.infrastructure;
import com.amhs.integration.VehicleListening;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * What brings the open ports back in line with the rows, on a schedule.
 *
 * <p>Its own bean, and switchable, for the reason the equipment poller's is -- and here it is not
 * only noise. A test run keeps several application contexts open at once, and a port can only be
 * held by one of them: with the schedule on, a context belonging to one test class binds the port
 * another test class has just configured, and then answers that test's gateway with its own key
 * material. The handshake fails, correctly, against the wrong trust store, and the failure reads
 * exactly like the defect the test was written to catch.
 *
 * <p>{@code amhs.device.tcp.auto-listen=false} turns it off, and the tests that own a port call
 * {@code listen()} themselves. On by default: a controller that only opened its ports at startup
 * would need a restart to accept a vehicle somebody wired up this morning.
 */
@Component
@ConditionalOnProperty(name="amhs.device.tcp.auto-listen",havingValue="true",matchIfMissing=true)
class VehicleListenRefresh {
 private final VehicleListening listening;
 VehicleListenRefresh(VehicleListening listening){ this.listening=listening; }

 @Scheduled(fixedDelayString="${amhs.device.tcp.listen-refresh-ms:5000}")
 void tick(){ listening.listen(); }
}
