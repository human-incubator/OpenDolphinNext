package open.dolphin.rest.orca;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.lang.reflect.Field;
import java.sql.Timestamp;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ChartEditSessionRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-10-04T01:00:00Z");

    @Test
    void sameUserFromAnotherTabTakesOverActiveLeaseWithoutForce() throws Exception {
        ChartEditSessionRepository repository = repositoryWithActiveLease("F001:doctor01", "tab-old");

        ChartEditSessionRepository.EditSessionResult result = repository.acquire(command("F001:doctor01", "tab-new"));

        assertTrue(result.ok());
        assertEquals("owned", result.lockStatus());
        assertEquals("tab-new", result.ownerTabSessionId());
    }

    @Test
    void differentUserStillGetsConflictWithHolderDetails() throws Exception {
        ChartEditSessionRepository repository = repositoryWithActiveLease("F001:doctor02", "tab-old");

        ChartEditSessionRepository.EditSessionResult result = repository.acquire(command("F001:doctor01", "tab-new"));

        assertFalse(result.ok());
        assertEquals("other-editor", result.lockStatus());
        assertEquals("RUN-OLD", result.ownerRunId());
        assertEquals(NOW.plusSeconds(200), result.expiresAt());
    }

    private static ChartEditSessionRepository.EditSessionCommand command(String actor, String tab) {
        return new ChartEditSessionRepository.EditSessionCommand(
                "F001", "P001", "patient:P001", actor, "RUN-NEW", tab, null, false, 300, NOW);
    }

    private static ChartEditSessionRepository repositoryWithActiveLease(String ownerUser, String ownerTab)
            throws Exception {
        Query query = mock(Query.class, RETURNS_SELF);
        when(query.getSingleResult()).thenReturn(new Object[]{
                1L, "lease-old", ownerUser, "RUN-OLD", ownerTab,
                Timestamp.from(NOW.minusSeconds(100)),
                Timestamp.from(NOW.minusSeconds(100)),
                Timestamp.from(NOW.plusSeconds(200)),
                null});
        when(query.executeUpdate()).thenReturn(1);
        EntityManager entityManager = mock(EntityManager.class);
        when(entityManager.createNativeQuery(anyString())).thenReturn(query);
        ChartEditSessionRepository repository = new ChartEditSessionRepository();
        Field field = ChartEditSessionRepository.class.getDeclaredField("entityManager");
        field.setAccessible(true);
        field.set(repository, entityManager);
        return repository;
    }
}
