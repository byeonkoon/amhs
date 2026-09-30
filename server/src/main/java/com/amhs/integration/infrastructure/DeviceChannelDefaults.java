package com.amhs.integration.infrastructure;
import com.amhs.integration.DeviceChannel;
import com.amhs.integration.DeviceCommand;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
/**
 * Stands in until real adapters exist, and steps aside as soon as one is registered.
 *
 * <p>Declared as a bean method rather than a scanned component: the missing-bean condition is
 * evaluated while scanning, before any adapter is known, so a scanned stand-in would either
 * always win or always lose depending on scan order.
 */
@Configuration(proxyBeanMethods=false)
class DeviceChannelDefaults {
    /**
     * Reports NOT_DELIVERED rather than doubt: no link was opened and no byte was written, so
     * repeating the command cannot duplicate anything at the device.
     */
    @Bean @ConditionalOnMissingBean(DeviceChannel.class)
    DeviceChannel unconfiguredDeviceChannel() {
        return (DeviceCommand command) -> DeviceChannel.Verdict.notDelivered("ADAPTER_NOT_IMPLEMENTED");
    }
}
