package dev.asambataiden.iam;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** Fast smoke-level class test. Database-backed context is exercised in *IT. */
class IamApplicationTest {
    @Test
    void applicationEntryPointIsPresent() {
        assertNotNull(IamApplication.class);
    }
}
