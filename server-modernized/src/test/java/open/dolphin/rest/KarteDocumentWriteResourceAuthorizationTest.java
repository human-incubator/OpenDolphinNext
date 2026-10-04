package open.dolphin.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.WebApplicationException;
import open.dolphin.infomodel.DocumentModel;
import open.dolphin.infomodel.UserModel;
import open.dolphin.security.audit.AuthoritativeAuditRepository;
import open.dolphin.session.KarteServiceBean;
import open.dolphin.session.UserServiceBean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class KarteDocumentWriteResourceAuthorizationTest {

    @Mock
    KarteServiceBean karteServiceBean;

    @Mock
    AuthoritativeAuditRepository authoritativeAuditRepository;

    @Mock
    UserServiceBean userServiceBean;

    @Mock
    HttpServletRequest httpServletRequest;

    @Spy
    ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    KarteDocumentWriteResource resource;

    @BeforeEach
    void setUp() {
        lenient().when(authoritativeAuditRepository.isWritePathAvailable()).thenReturn(true);
        lenient().when(httpServletRequest.getRemoteUser()).thenReturn("FAC_A:doctor01");
    }

    @Test
    void postDocumentRejectsParentDocumentFromOtherFacility() {
        when(karteServiceBean.findFacilityIdByKarteId(501L)).thenReturn("FAC_A");
        when(karteServiceBean.findFacilityIdByDocId(9001L)).thenReturn("FAC_B");

        assertStatus(() -> resource.postDocument("""
                {"karteBean":{"id":501},"docInfoModel":{"parentPk":9001}}
                """), 403);
        verify(karteServiceBean, never()).addDocument(any());
    }

    @Test
    void postDocumentRequiresKarte() {
        assertStatus(() -> resource.postDocument("""
                {"docInfoModel":{"docId":"abc"}}
                """), 400);
        verify(karteServiceBean, never()).addDocument(any());
    }

    @Test
    void putDocumentRejectsOtherFacilityDocument() {
        when(karteServiceBean.findFacilityIdByDocId(42L)).thenReturn("FAC_B");

        assertStatus(() -> resource.putDocument("""
                {"id":42,"karteBean":{"id":501}}
                """), 403);
        verify(karteServiceBean, never()).updateDocument(any());
    }

    @Test
    void postDocumentUsesSessionUserAsCreator() throws Exception {
        UserModel actor = new UserModel();
        actor.setId(601L);
        actor.setUserId("FAC_A:doctor01");
        when(userServiceBean.getUser("FAC_A:doctor01")).thenReturn(actor);
        when(karteServiceBean.findFacilityIdByKarteId(501L)).thenReturn("FAC_A");
        when(karteServiceBean.addDocument(any())).thenReturn(1L);

        resource.postDocument("""
                {"karteBean":{"id":501},"userModel":{"id":999},"docInfoModel":{"docId":"abc"}}
                """);

        ArgumentCaptor<DocumentModel> captor = ArgumentCaptor.forClass(DocumentModel.class);
        verify(karteServiceBean).addDocument(captor.capture());
        assertThat(captor.getValue().getUserModel().getId()).isEqualTo(601L);
    }

    private static void assertStatus(org.assertj.core.api.ThrowableAssert.ThrowingCallable callable, int status) {
        assertThatThrownBy(callable)
                .isInstanceOf(WebApplicationException.class)
                .satisfies(ex -> assertThat(((WebApplicationException) ex).getResponse().getStatus()).isEqualTo(status));
    }
}
