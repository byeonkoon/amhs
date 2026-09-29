package com.amhs;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
class ModuleBoundariesTest {
 @Test void moduleBoundariesAreValid() { ApplicationModules.of(AmhsApplication.class).verify(); }
}
