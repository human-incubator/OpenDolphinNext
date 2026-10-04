package open.dolphin.rest;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.WebApplicationException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import open.dolphin.infomodel.StampModel;
import open.dolphin.infomodel.UserModel;
import open.dolphin.security.audit.AuditEventPayload;
import open.dolphin.security.audit.AuditTrailService;
import open.dolphin.session.StampServiceBean;
import open.dolphin.session.UserServiceBean;
import open.dolphin.session.framework.SessionTraceContext;
import open.dolphin.session.framework.SessionTraceManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class StampResourceTest {

    private static final long ACTOR_USER_PK = 101L;

    @Mock
    StampServiceBean stampServiceBean;

    @Mock
    UserServiceBean userServiceBean;

    @Mock
    AuditTrailService auditTrailService;

    @Mock
    SessionTraceManager sessionTraceManager;

    @Mock
    HttpServletRequest httpServletRequest;

    @InjectMocks
    StampResource resource;

    @Captor
    ArgumentCaptor<AuditEventPayload> auditCaptor;

    @BeforeEach
    void setUp() {
        lenient().when(httpServletRequest.getHeader(anyString())).thenReturn(null);
        lenient().when(httpServletRequest.getRemoteUser()).thenReturn("FAC001:user01");
        lenient().when(userServiceBean.getUser("FAC001:user01")).thenReturn(actorUser());
        lenient().when(httpServletRequest.getRemoteAddr()).thenReturn("127.0.0.1");
        lenient().when(httpServletRequest.getHeader("User-Agent")).thenReturn("JUnit");
        lenient().when(httpServletRequest.getHeader("X-Request-Id")).thenReturn("req-1");
        lenient().when(httpServletRequest.isUserInRole("ADMIN")).thenReturn(false);
        lenient().when(sessionTraceManager.current()).thenReturn(null);
    }

    @Test
    void deleteStampRecordsAuditOnSuccess() {
        when(httpServletRequest.getRequestURI()).thenReturn("/stamp/id/1");
        when(stampServiceBean.getStamp("1")).thenReturn(stamp("1", ACTOR_USER_PK));
        when(stampServiceBean.removeStamp("1")).thenReturn(1);
        SessionTraceContext trace = new SessionTraceContext("trace-1", Instant.now(), "DELETE_STAMP", Map.of());
        when(sessionTraceManager.current()).thenReturn(trace);

        resource.deleteStamp("1");

        verify(auditTrailService).record(auditCaptor.capture());
        AuditEventPayload payload = auditCaptor.getValue();
        assertThat(payload.getAction()).isEqualTo("STAMP_DELETE_SINGLE");
        assertThat(payload.getResource()).isEqualTo("/stamp/id/1");
        Map<String, Object> details = payload.getDetails();
        assertThat(details.get("status")).isEqualTo("success");
        assertThat(stampIds(details)).containsExactly("1");
        assertThat(details.get("deletedCount")).isEqualTo(1);
        assertThat(details.get("facilityId")).isEqualTo("FAC001");
        assertThat(details.get("userId")).isEqualTo("user01");
        assertThat(details.get("traceId")).isEqualTo("trace-1");
    }

    @Test
    void deleteStampThrowsNotFoundAndAuditsWhenMissing() {
        when(httpServletRequest.getRequestURI()).thenReturn("/stamp/id/404");
        when(stampServiceBean.getStamp("404")).thenReturn(null);

        assertThatThrownBy(() -> resource.deleteStamp("404")).isInstanceOf(NotFoundException.class);

        verify(auditTrailService).record(auditCaptor.capture());
        Map<String, Object> details = auditCaptor.getValue().getDetails();
        assertThat(details.get("status")).isEqualTo("failed");
        assertThat(details.get("reason")).isEqualTo("stamp_not_found");
        assertThat(stampIds(details)).containsExactly("404");
    }

    @Test
    void deleteStampsRecordsAuditOnSuccess() {
        when(httpServletRequest.getRequestURI()).thenReturn("/stamp/list/1,2");
        when(stampServiceBean.getStamp(anyList())).thenAnswer(invocation -> {
            List<String> ids = invocation.getArgument(0);
            List<StampModel> models = new ArrayList<>();
            for (String id : ids) {
                models.add(stamp(id, ACTOR_USER_PK));
            }
            return models;
        });
        when(stampServiceBean.removeStamp(anyList())).thenAnswer(invocation -> ((List<?>) invocation.getArgument(0)).size());

        resource.deleteStamps("1,2");

        verify(auditTrailService).record(auditCaptor.capture());
        AuditEventPayload payload = auditCaptor.getValue();
        assertThat(payload.getAction()).isEqualTo("STAMP_DELETE_BULK");
        Map<String, Object> details = payload.getDetails();
        assertThat(details.get("status")).isEqualTo("success");
        assertThat(stampIds(details)).containsExactly("1", "2");
        assertThat(details.get("deletedCount")).isEqualTo(2);
    }

    @Test
    void deleteStampsThrowsWhenAnyIdMissing() {
        when(httpServletRequest.getRequestURI()).thenReturn("/stamp/list/1,2");
        when(stampServiceBean.getStamp(anyList())).thenAnswer(invocation -> {
            List<String> ids = invocation.getArgument(0);
            List<StampModel> models = new ArrayList<>();
            for (String id : ids) {
                models.add("2".equals(id) ? null : stamp(id, ACTOR_USER_PK));
            }
            return models;
        });

        assertThatThrownBy(() -> resource.deleteStamps("1,2")).isInstanceOf(NotFoundException.class);

        verify(auditTrailService).record(auditCaptor.capture());
        Map<String, Object> details = auditCaptor.getValue().getDetails();
        assertThat(details.get("status")).isEqualTo("failed");
        assertThat(details.get("reason")).isEqualTo("missing_ids:2");
        assertThat(stampIds(details)).containsExactly("1", "2");
    }

    @Test
    void getStampTreeReturnsForbiddenWhenOtherUserPkSpecified() {
        assertForbidden(() -> resource.getStampTree(String.valueOf(ACTOR_USER_PK + 1)));
        verifyNoInteractions(stampServiceBean);
    }

    @Test
    void getStampReturnsForbiddenWhenOwnerMismatchesActor() {
        when(stampServiceBean.getStamp("other-stamp")).thenReturn(stamp("other-stamp", ACTOR_USER_PK + 1));

        assertForbidden(() -> resource.getStamp("other-stamp"));
    }

    @Test
    void putStampOverridesPayloadUserIdWithActorUserPk() throws Exception {
        String json = "{\"id\":\"s-1\",\"userId\":999,\"entity\":\"med_order\",\"stampBytes\":\"AQ==\"}";
        when(stampServiceBean.putStampForActor(any(StampModel.class), anyLong())).thenReturn("s-1");

        resource.putStamp(json);

        var stampCaptor = org.mockito.ArgumentCaptor.forClass(StampModel.class);
        var userPkCaptor = org.mockito.ArgumentCaptor.forClass(Long.class);
        verify(stampServiceBean).putStampForActor(stampCaptor.capture(), userPkCaptor.capture());
        assertThat(userPkCaptor.getValue()).isEqualTo(ACTOR_USER_PK);
        assertThat(stampCaptor.getValue().getUserId()).isEqualTo(ACTOR_USER_PK);
    }

    private static void assertForbidden(org.assertj.core.api.ThrowableAssert.ThrowingCallable callable) {
        assertThatThrownBy(callable)
                .isInstanceOf(WebApplicationException.class)
                .satisfies(ex -> assertThat(((WebApplicationException) ex).getResponse().getStatus()).isEqualTo(403));
    }

    @Test
    void putStampRejectsOverwritingOtherUsersStamp() {
        when(stampServiceBean.getStamp("victim-stamp")).thenReturn(stamp("victim-stamp", 999L));

        assertThatThrownBy(() -> resource.putStamp("{\"id\":\"victim-stamp\",\"userId\":101}"))
                .isInstanceOf(WebApplicationException.class)
                .satisfies(ex -> assertThat(((WebApplicationException) ex).getResponse().getStatus()).isEqualTo(403));
        verify(stampServiceBean, never()).putStampForActor(org.mockito.ArgumentMatchers.any(StampModel.class),
                org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void subscribeTreesRejectsTreeNotVisibleToFacility() {
        when(stampServiceBean.isPublishedTreeSubscribable(55L, "FAC001")).thenReturn(false);

        assertThatThrownBy(() -> resource.subscribeTrees("{\"list\":[{\"treeId\":55}]}"))
                .isInstanceOf(WebApplicationException.class)
                .satisfies(ex -> assertThat(((WebApplicationException) ex).getResponse().getStatus()).isEqualTo(404));
        verify(stampServiceBean, never()).subscribeTreesForActor(anyList(), org.mockito.ArgumentMatchers.anyLong());
    }

    private static UserModel actorUser() {
        UserModel actor = new UserModel();
        actor.setId(ACTOR_USER_PK);
        actor.setUserId("FAC001:user01");
        actor.setCommonName("user01");
        return actor;
    }

    private static StampModel stamp(String id, long userPk) {
        StampModel model = new StampModel();
        model.setId(id);
        model.setUserId(userPk);
        return model;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> stampIds(Map<String, Object> details) {
        return (List<Object>) details.get("stampIds");
    }
}
