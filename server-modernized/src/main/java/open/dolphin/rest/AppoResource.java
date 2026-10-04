package open.dolphin.rest;

import java.io.IOException;
import jakarta.inject.Inject;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import open.dolphin.infomodel.AppoList;
import open.dolphin.infomodel.AppointmentModel;
import open.dolphin.infomodel.UserModel;
import open.dolphin.session.AppoServiceBean;
import open.dolphin.session.UserServiceBean;

/**
 * REST Web Service
 *
 * @author Kazushi Minagawa, Digital Globe, Inc.
 */
@Path("/appo")
public class AppoResource extends AbstractResource {
    
    @Inject
    private AppoServiceBean appoServiceBean;

    @Inject
    private UserServiceBean userServiceBean;

    /** Creates a new instance of AppoResource */
    public AppoResource() {
    }

    @PUT
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.TEXT_PLAIN)
    public String putXml(@Context HttpServletRequest request, String json) throws IOException {
        String fid = requireActorFacility(request);

        AppoList list = readJson(json, AppoList.class);
        if (list != null && list.getList() != null && !list.getList().isEmpty()) {
            // 記載者 (creator) はクライアント指定を無視し、セッションのユーザーにする
            UserModel actor = resolveActorUser(request);
            for (AppointmentModel model : list.getList()) {
                if (model != null) {
                    model.setUserModel(actor);
                }
            }
        }

        int count = appoServiceBean.putAppointmentsForFacility(fid, list.getList());
        if (count == 0 && list.getList() != null && !list.getList().isEmpty()) {
            throw new NotFoundException("Appointment not found");
        }
        String cntStr = String.valueOf(count);
        debug(cntStr);

        return cntStr;
    }

    private UserModel resolveActorUser(HttpServletRequest request) {
        String remoteUser = requireRemoteUser(request);
        UserModel actor = null;
        try {
            actor = userServiceBean != null ? userServiceBean.getUser(remoteUser) : null;
        } catch (RuntimeException ex) {
            actor = null;
        }
        if (actor == null) {
            throw restError(request, Response.Status.UNAUTHORIZED, "unauthorized", "Authenticated user could not be resolved.");
        }
        return actor;
    }

}
