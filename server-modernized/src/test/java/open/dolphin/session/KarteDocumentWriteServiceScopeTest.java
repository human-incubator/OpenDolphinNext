package open.dolphin.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.persistence.EntityManager;
import jakarta.ws.rs.WebApplicationException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import open.dolphin.infomodel.DocumentModel;
import open.dolphin.infomodel.IInfoModel;
import open.dolphin.infomodel.KarteBean;
import open.dolphin.infomodel.ModuleModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * updateDocument が他文書・他カルテの行を上書きできないことを検証する。
 */
class KarteDocumentWriteServiceScopeTest {

    private KarteDocumentWriteService service;
    private EntityManager em;

    @BeforeEach
    void setUp() throws Exception {
        service = new KarteDocumentWriteService();
        em = mock(EntityManager.class);
        Field field = KarteDocumentWriteService.class.getDeclaredField("em");
        field.setAccessible(true);
        field.set(service, em);
    }

    @Test
    void updateDocumentRejectsForeignModuleId() {
        when(em.find(DocumentModel.class, 5L)).thenReturn(current(5L, 1L, 10L));
        DocumentModel incoming = document(5L, 1L, 99L);

        Throwable thrown = catchThrowable(() -> service.updateDocument(incoming));

        assertThat(thrown).isInstanceOf(WebApplicationException.class);
        assertThat(((WebApplicationException) thrown).getResponse().getStatus()).isEqualTo(403);
        verify(em, never()).merge(any());
    }

    @Test
    void updateDocumentRejectsKarteChange() {
        when(em.find(DocumentModel.class, 5L)).thenReturn(current(5L, 1L, 10L));
        DocumentModel incoming = document(5L, 2L, 10L);

        Throwable thrown = catchThrowable(() -> service.updateDocument(incoming));

        assertThat(thrown).isInstanceOf(WebApplicationException.class);
        assertThat(((WebApplicationException) thrown).getResponse().getStatus()).isEqualTo(403);
        verify(em, never()).merge(any());
    }

    private static DocumentModel current(long id, long karteId, long moduleId) {
        DocumentModel doc = document(id, karteId, moduleId);
        doc.setStatus(IInfoModel.STATUS_TMP);
        return doc;
    }

    private static DocumentModel document(long id, long karteId, long moduleId) {
        DocumentModel doc = new DocumentModel();
        doc.setId(id);
        doc.setStatus(IInfoModel.STATUS_TMP);
        KarteBean karte = new KarteBean();
        karte.setId(karteId);
        doc.setKarteBean(karte);
        ModuleModel module = new ModuleModel();
        module.setId(moduleId);
        List<ModuleModel> modules = new ArrayList<>();
        modules.add(module);
        doc.setModules(modules);
        return doc;
    }
}
