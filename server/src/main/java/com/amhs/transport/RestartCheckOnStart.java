package com.amhs.transport;
import com.amhs.platform.OperationOptionConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Opens the restart check for every site as the server comes up -- after the database is migrated
 * and before any scheduled pass can run, so not one pass progresses on a state nobody has compared.
 * On by default; {@code amhs.restart-check.on-start=false} turns it off (tests open it by hand).
 */
@Component
@ConditionalOnProperty(name="amhs.restart-check.on-start",havingValue="true",matchIfMissing=true)
class RestartCheckOnStart implements SmartInitializingSingleton {
 private static final Logger log=LoggerFactory.getLogger(RestartCheckOnStart.class);
 private final RestartCheck check; private final OperationOptionConfiguration options;
 RestartCheckOnStart(RestartCheck check,OperationOptionConfiguration options){ this.check=check; this.options=options; }

 @Override public void afterSingletonsInstantiated(){
  for(long siteId:options.sites()){
   try{
    var state=check.begin(siteId);
    if(state.pending()) log.warn("site {}: work was in progress when the server stopped; it waits for the restart check ({} item(s))",
     siteId,state.items().size());
    else log.info("site {}: nothing was in progress; no restart check needed",siteId);
   }catch(RuntimeException e){
    // One site that cannot be judged must neither keep the server down nor carry on uncompared: it
    // is held, and the others are still checked.
    log.error("site {}: the restart check could not be made; the site is held until somebody confirms it",siteId,e);
    try{ check.hold(siteId); }
    catch(RuntimeException held){ log.error("site {}: it could not even be held; do not run it until it has been checked",siteId,held); }
   }
  }
 }
}
