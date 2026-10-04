package open.dolphin.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.lang.reflect.Method;
import open.dolphin.rest.jackson.LegacyObjectMapperProducer;
import open.dolphin.security.audit.AuthoritativeAuditRepository;
import open.dolphin.security.audit.AuditTrailService;
import open.dolphin.session.KarteServiceBean;
import open.dolphin.session.PVTServiceBean;
import open.dolphin.session.UserServiceBean;
import open.dolphin.infomodel.UserModel;
import jakarta.servlet.http.HttpServletRequest;
import open.dolphin.session.framework.SessionTraceManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class KarteResourceDocumentContractTest {

    @Mock
    KarteServiceBean karteServiceBean;

    @Mock
    PVTServiceBean pvtServiceBean;

    @Mock
    AuditTrailService auditTrailService;

    @Mock
    AuthoritativeAuditRepository authoritativeAuditRepository;

    @Mock
    SessionTraceManager sessionTraceManager;

    @Mock
    UserServiceBean userServiceBean;

    @Mock
    HttpServletRequest httpServletRequest;

    @Spy
    ObjectMapper objectMapper = new LegacyObjectMapperProducer().provideLegacyAwareMapper();

    @InjectMocks
    KarteDocumentWriteResource resource;

    @Test
    void postDocumentReturnsPlainTextNumericPk() throws Exception {
        when(authoritativeAuditRepository.isWritePathAvailable()).thenReturn(true);
        when(karteServiceBean.addDocument(any())).thenReturn(123L);
        stubSameFacilityActor();

        String response = resource.postDocument("{\"karteBean\":{\"id\":501}}");

        assertThat(response).isEqualTo("123");
        assertProducesTextPlain("postDocument", String.class);
        verify(karteServiceBean).addDocument(any());
    }

    @Test
    void putDocumentReturnsPlainTextNumericPk() throws Exception {
        when(authoritativeAuditRepository.isWritePathAvailable()).thenReturn(true);
        when(karteServiceBean.updateDocument(any())).thenReturn(123L);
        stubSameFacilityActor();
        when(karteServiceBean.findFacilityIdByDocId(42L)).thenReturn("FAC_A");

        String response = resource.putDocument("{\"id\":42,\"karteBean\":{\"id\":501}}");

        assertThat(response).isEqualTo("123");
        assertProducesTextPlain("putDocument", String.class);
        verify(karteServiceBean).updateDocument(any());
    }

    @Test
    void postDocumentReturnsServiceUnavailableWhenAuditWritePathIsUnavailable() {
        when(authoritativeAuditRepository.isWritePathAvailable()).thenReturn(false);

        assertThatThrownBy(() -> resource.postDocument("{}"))
                .isInstanceOf(WebApplicationException.class)
                .satisfies(ex -> assertThat(((WebApplicationException) ex).getResponse().getStatus()).isEqualTo(503));
        verify(karteServiceBean, never()).addDocument(any());
    }

    @Test
    void putTitleReturnsServiceUnavailableWhenAuditWritePathIsUnavailable() {
        when(authoritativeAuditRepository.isWritePathAvailable()).thenReturn(false);

        assertThatThrownBy(() -> resource.putTitle("300", "after"))
                .isInstanceOf(WebApplicationException.class)
                .satisfies(ex -> assertThat(((WebApplicationException) ex).getResponse().getStatus()).isEqualTo(503));
        verify(karteServiceBean, never()).updateTitle(300L, "after");
    }

    private void stubSameFacilityActor() {
        UserModel actor = new UserModel();
        actor.setId(601L);
        actor.setUserId("FAC_A:doctor01");
        when(httpServletRequest.getRemoteUser()).thenReturn("FAC_A:doctor01");
        when(userServiceBean.getUser("FAC_A:doctor01")).thenReturn(actor);
        when(karteServiceBean.findFacilityIdByKarteId(501L)).thenReturn("FAC_A");
    }

    private static void assertProducesTextPlain(String methodName, Class<?>... parameterTypes) throws Exception {
        Method method = KarteDocumentWriteResource.class.getMethod(methodName, parameterTypes);
        Produces produces = method.getAnnotation(Produces.class);
        assertThat(produces).isNotNull();
        assertThat(produces.value()).contains(MediaType.TEXT_PLAIN);
    }
}
