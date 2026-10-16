package com.amhs.equipment.infrastructure;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * What keeps the equipment links going round.
 *
 * <p>Its own bean, and switchable, for the reason the simulation loop's is: a test that steps the
 * links by hand must be the only thing stepping them. Several application contexts live at once in
 * a test run and none of them is closed until the end, so a schedule left on means half a dozen
 * pollers reading the same wiring and writing statements against the same ports -- which is not
 * the arrangement being tested, and not one any site has.
 *
 * <p>{@code amhs.device.equipment.auto-poll=false} turns it off. On by default, because a site
 * that did not poll its equipment would learn nothing about it.
 */
@Component
@ConditionalOnProperty(name="amhs.device.equipment.auto-poll",havingValue="true",matchIfMissing=true)
class EquipmentPolling {
 private final EquipmentLinks links;
 EquipmentPolling(EquipmentLinks links){ this.links=links; }

 @Scheduled(fixedDelayString="${amhs.device.equipment.tick-ms:200}")
 void tick(){ links.tick(); }
}
