package open.dolphin.rest;

import java.io.IOException;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.logging.Level;
import java.util.logging.Logger;
import jakarta.inject.Inject;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import open.dolphin.infomodel.FacilityModel;
import open.dolphin.infomodel.IInfoModel;
import open.dolphin.infomodel.RoleModel;
import open.dolphin.infomodel.UserModel;
import open.dolphin.rest.dto.CurrentUserResponse;
import open.dolphin.rest.dto.UserMutationRequest;
import open.dolphin.rest.support.CurrentUserResponseMapper;
import open.dolphin.rest.support.UserMutationRequestMapper;
import open.dolphin.session.UserServiceBean;

/**
 * REST Web Service
 *
 * @author Kazushi Minagawa, Digital Globe, Inc.
 */
@Path("/user")
public class UserResource extends AbstractResource {

    @Inject
    private UserServiceBean userServiceBean;

    /** Creates a new instance of UserResource */
    public UserResource() {
    }

    @GET
    @Path("/{userId}")
    @Produces(MediaType.APPLICATION_JSON)
    public CurrentUserResponse getUser(@Context HttpServletRequest servletReq,
            @PathParam("userId") String userId) throws IOException {
        String remoteUser = servletReq != null ? servletReq.getRemoteUser() : null;
        if (remoteUser == null || remoteUser.isBlank()) {
            throw restError(servletReq, Response.Status.UNAUTHORIZED, "unauthorized", "Authentication required.");
        }
        UserModel result = loadUserQuietly(userId);
        if (result == null) {
            throw restError(servletReq, Response.Status.NOT_FOUND, "not_found", "Requested resource was not found.");
        }
        if (!canReadUser(remoteUser, result.getUserId())) {
            Logger.getLogger("open.dolphin").log(Level.WARNING, "Denied user read for actor={0}", new Object[]{remoteUser});
            throw restError(servletReq, Response.Status.NOT_FOUND, "not_found", "Requested resource was not found.");
        }
        return CurrentUserResponseMapper.from(result);
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public List<CurrentUserResponse> getAllUser(@Context HttpServletRequest servletReq) {
        
//s.oh^ 脆弱性対応
        // 管理者権限かチェック
        HttpServletRequest req = (HttpServletRequest)servletReq;
        String remoteUser = req.getRemoteUser();
        if(remoteUser == null || !userServiceBean.isAdmin(remoteUser)) {
            Logger.getLogger("open.dolphin").log(Level.WARNING, "Not an administrator authority:{0}", new Object[]{remoteUser});
            return null;
        }
//s.oh$
        
        String fid = getRemoteFacility(servletReq.getRemoteUser());
        debug(fid);

        List<UserModel> result = userServiceBean.getAllUser(fid);
        return result.stream()
                .map(CurrentUserResponseMapper::from)
                .collect(Collectors.toList());
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.TEXT_PLAIN)
    public String postUser(@Context HttpServletRequest servletReq, String json) throws IOException {
        UserMutationRequest requestPayload = readJson(json, UserMutationRequest.class);
        rejectFactor2AuthInput(servletReq, requestPayload);

//s.oh^ 脆弱性対応
        // 管理者権限かチェック
        HttpServletRequest req = (HttpServletRequest)servletReq;
        String remoteUser = req.getRemoteUser();
        if(remoteUser == null || !userServiceBean.isAdmin(remoteUser)) {
            Logger.getLogger("open.dolphin").log(Level.WARNING, "Not an administrator authority:{0}", new Object[]{remoteUser});
            return "0";
        }
//s.oh$
        
        String fid = getRemoteFacility(servletReq.getRemoteUser());
        debug(fid);

        UserModel model = UserMutationRequestMapper.toModel(requestPayload);
        if (model == null) {
            throw restError(req, Response.Status.BAD_REQUEST, "invalid_request", "user payload is required.");
        }
        // 施設はセッションから決める。userId の施設プレフィックスが自施設と異なる場合は拒否する。
        model.setUserId(resolveNewUserId(req, fid, model.getUserId()));
        ensureRolesGrantable(req, remoteUser, null, model.getRoles());

        // クライアント指定の facility (id 含む) は使わない
        FacilityModel facilityModel = new FacilityModel();
        facilityModel.setFacilityId(fid);
        model.setFacilityModel(facilityModel);

        // 関係を構築する
        List<RoleModel> roles = model.getRoles();
        if (roles != null) {
            for (RoleModel role : roles) {
                role.setUserModel(model);
                role.setUserId(model.getUserId());
            }
        }

        int result = userServiceBean.addUser(model);
        String cntStr = String.valueOf(result);
        debug(cntStr);
        
        return cntStr;
    }

    @PUT
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.TEXT_PLAIN)
    public String putUser(@Context HttpServletRequest servletReq, String json) throws IOException {

        UserMutationRequest requestPayload = readJson(json, UserMutationRequest.class);
        rejectFactor2AuthInput(servletReq, requestPayload);
        UserModel model = UserMutationRequestMapper.toModel(requestPayload);

        HttpServletRequest req = (HttpServletRequest)servletReq;
        String remoteUser = requireRemoteUser(req);
        String actorFacility = requireActorFacility(req);
        UserModel current = loadUserByPkQuietly(model != null ? model.getId() : 0L);
        if (current == null) {
            throw userNotFound(req, model != null ? model.getId() : null);
        }
        ensureFacilityMatchOr404(actorFacility, facilityIdOf(current), "userPk", current.getId(), req);

        boolean admin = userServiceBean.isAdmin(remoteUser);
        if (admin) {
            ensureRolesGrantable(req, remoteUser, current.getRoles(), model.getRoles());
        } else {
            if (!remoteUser.equals(current.getUserId())) {
                Logger.getLogger("open.dolphin").log(Level.WARNING, "User ID is different:{0},{1}",
                        new Object[]{remoteUser, current.getUserId()});
                throw restError(req, Response.Status.FORBIDDEN, "forbidden", "You can update only your own profile.");
            }
            if (hasRoleChange(current.getRoles(), model.getRoles())) {
                Logger.getLogger("open.dolphin").log(Level.WARNING, "Role update is forbidden for non-admin:{0}",
                        new Object[]{remoteUser});
                throw restError(req, Response.Status.FORBIDDEN, "forbidden", "Role update requires administrator privilege.");
            }
        }
        normalizeUserForUpdate(model, current);
        
        // 関係を構築する
        List<RoleModel> roles = model.getRoles();
        if (roles != null) {
            roles.forEach(role -> {
                role.setUserModel(model);
                role.setUserId(model.getUserId());
            });
        }

        int result = userServiceBean.updateUser(model);
        String cntStr = String.valueOf(result);
        debug(cntStr);

        return cntStr;
    }

    @DELETE
    @Path("/{userId}")
    public void deleteUser(@Context HttpServletRequest servletReq, @PathParam("userId") String userId) {
        
        HttpServletRequest req = (HttpServletRequest)servletReq;
        requireAdmin(req, userServiceBean);
        String actorFacility = requireActorFacility(req);
        UserModel target = loadUserQuietly(userId);
        if (target == null) {
            throw userNotFound(req, userId);
        }
        ensureFacilityMatchOr404(actorFacility, facilityIdOf(target), "userId", userId, req);
        if (UserRoleGrantPolicy.containsSystemAdminRole(roleNames(target.getRoles()))
                && !userServiceBean.isSystemAdmin(req.getRemoteUser())) {
            Logger.getLogger("open.dolphin").log(Level.WARNING, "Denied deleting system administrator by actor={0}",
                    new Object[]{req.getRemoteUser()});
            throw restError(req, Response.Status.FORBIDDEN, "forbidden",
                    "Deleting a system administrator requires system administrator privilege.");
        }

        int result = userServiceBean.removeUser(target.getUserId());

        debug(String.valueOf(result));
    }

    @PUT
    @Path("/facility")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.TEXT_PLAIN)
    public String putFacility(@Context HttpServletRequest servletReq, String json) throws IOException {
        HttpServletRequest req = (HttpServletRequest) servletReq;
        UserMutationRequest requestPayload = readJson(json, UserMutationRequest.class);
        rejectFactor2AuthInput(req, requestPayload);
        requireAdmin(req, userServiceBean);
        String actorFacility = requireActorFacility(req);

        UserModel model = UserMutationRequestMapper.toModel(requestPayload);
        FacilityModel requestedFacility = model != null ? model.getFacilityModel() : null;
        FacilityModel currentFacility = loadFacilityByPkQuietly(requestedFacility != null ? requestedFacility.getId() : 0L);
        if (currentFacility == null) {
            throw facilityNotFound(req, requestedFacility != null ? requestedFacility.getId() : null);
        }
        ensureFacilityMatchOr404(actorFacility, currentFacility.getFacilityId(), "facilityPk", currentFacility.getId(), req);
        requestedFacility.setId(currentFacility.getId());
        requestedFacility.setFacilityId(actorFacility);

        int result = userServiceBean.updateFacility(model);
        String cntStr = String.valueOf(result);
        debug(cntStr);

        return cntStr;
    }

    private void rejectFactor2AuthInput(HttpServletRequest request, UserMutationRequest requestPayload) {
        if (requestPayload == null || requestPayload.getFactor2Auth() == null) {
            return;
        }
        throw restError(request, Response.Status.BAD_REQUEST, "invalid_request", "factor2Auth は受け付けません。");
    }
    
//s.oh^ 脆弱性対応
    @GET
    @Path("/name/{userId}")
    @Produces(MediaType.TEXT_PLAIN)
    public String getUserName(@Context HttpServletRequest servletReq, @PathParam("userId") String userId) throws IOException {
        String remoteUser = servletReq != null ? servletReq.getRemoteUser() : null;
        if (remoteUser == null || remoteUser.isBlank()) {
            throw restError(servletReq, Response.Status.UNAUTHORIZED, "unauthorized", "Authentication required.");
        }
        if (!canReadUser(remoteUser, userId)) {
            Logger.getLogger("open.dolphin").log(Level.WARNING, "Denied user name read for actor={0}", new Object[]{remoteUser});
            throw restError(servletReq, Response.Status.NOT_FOUND, "not_found", "Requested resource was not found.");
        }
        return userServiceBean.getUserName(userId);
    }
//s.oh$

    private UserModel loadUserQuietly(String userId) {
        try {
            return userServiceBean.getUser(userId);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private UserModel loadUserByPkQuietly(long userPk) {
        try {
            return userServiceBean.getUserByPk(userPk);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private FacilityModel loadFacilityByPkQuietly(long facilityPk) {
        try {
            return userServiceBean.getFacilityByPk(facilityPk);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private String facilityIdOf(UserModel user) {
        if (user == null || user.getFacilityModel() == null) {
            return null;
        }
        return user.getFacilityModel().getFacilityId();
    }

    private void normalizeUserForUpdate(UserModel requested, UserModel current) {
        if (requested == null || current == null) {
            return;
        }
        String currentFacility = facilityIdOf(current);
        requested.setId(current.getId());
        requested.setFacilityModel(current.getFacilityModel());
        requested.setUserId(normalizeCompositeUserId(current.getUserId(), requested.getUserId(), currentFacility));
    }

    private String normalizeCompositeUserId(String currentUserId, String requestedUserId, String facilityId) {
        if (currentUserId == null || currentUserId.isBlank()) {
            return requestedUserId;
        }
        if (facilityId == null || facilityId.isBlank()) {
            return currentUserId;
        }
        int currentSeparator = currentUserId.indexOf(IInfoModel.COMPOSITE_KEY_MAKER);
        if (currentSeparator < 0) {
            return currentUserId;
        }
        if (requestedUserId == null || requestedUserId.isBlank()) {
            return currentUserId;
        }
        String normalized = requestedUserId.trim();
        int requestedSeparator = normalized.indexOf(IInfoModel.COMPOSITE_KEY_MAKER);
        String localPart = requestedSeparator >= 0 ? normalized.substring(requestedSeparator + 1) : normalized;
        if (localPart == null || localPart.isBlank()) {
            return currentUserId;
        }
        return facilityId + IInfoModel.COMPOSITE_KEY_MAKER + localPart.trim();
    }

    private WebApplicationException userNotFound(HttpServletRequest request, Object userIdentifier) {
        return notFound(request, "userIdentifier", userIdentifier);
    }

    private WebApplicationException facilityNotFound(HttpServletRequest request, Object facilityIdentifier) {
        return notFound(request, "facilityIdentifier", facilityIdentifier);
    }

    private WebApplicationException notFound(HttpServletRequest request, String key, Object value) {
        LinkedHashMap<String, Object> details = new LinkedHashMap<>();
        if (key != null && value != null) {
            details.put(key, value);
        }
        return restError(request, Response.Status.NOT_FOUND, "not_found", "Requested resource was not found.",
                details.isEmpty() ? null : details, null);
    }

    private String resolveNewUserId(HttpServletRequest request, String actorFacility, String requestedUserId) {
        if (actorFacility == null || actorFacility.isBlank()) {
            throw restError(request, Response.Status.UNAUTHORIZED, "facility_missing",
                    "Facility identifier is required for this operation.");
        }
        String normalized = requestedUserId != null ? requestedUserId.trim() : "";
        int separator = normalized.indexOf(IInfoModel.COMPOSITE_KEY_MAKER);
        String localPart = normalized;
        if (separator >= 0) {
            String requestedFacility = normalized.substring(0, separator).trim();
            if (!actorFacility.equals(requestedFacility)) {
                Logger.getLogger("open.dolphin").log(Level.WARNING,
                        "Denied cross-facility user creation: actorFacility={0}, requestedFacility={1}",
                        new Object[]{actorFacility, requestedFacility});
                throw restError(request, Response.Status.FORBIDDEN, "forbidden",
                        "Users can be created only in your own facility.");
            }
            localPart = normalized.substring(separator + 1).trim();
        }
        if (localPart.isEmpty() || localPart.contains(IInfoModel.COMPOSITE_KEY_MAKER)) {
            throw restError(request, Response.Status.BAD_REQUEST, "invalid_request", "userId is invalid.");
        }
        return actorFacility + IInfoModel.COMPOSITE_KEY_MAKER + localPart;
    }

    private void ensureRolesGrantable(HttpServletRequest request, String actor,
            List<RoleModel> currentRoles, List<RoleModel> requestedRoles) {
        boolean systemAdmin = userServiceBean.isSystemAdmin(actor);
        UserRoleGrantPolicy.Violation violation =
                UserRoleGrantPolicy.check(roleNames(currentRoles), roleNames(requestedRoles), systemAdmin);
        if (violation == UserRoleGrantPolicy.Violation.NONE) {
            return;
        }
        Logger.getLogger("open.dolphin").log(Level.WARNING, "Denied role grant ({0}) by actor={1}",
                new Object[]{violation, actor});
        if (violation == UserRoleGrantPolicy.Violation.ROLE_NOT_ALLOWED) {
            throw restError(request, Response.Status.FORBIDDEN, "role_not_allowed", "Requested role is not grantable.");
        }
        throw restError(request, Response.Status.FORBIDDEN, "forbidden",
                "System administrator role can be changed only by a system administrator.");
    }

    private static List<String> roleNames(List<RoleModel> roles) {
        if (roles == null) {
            return List.of();
        }
        return roles.stream()
                .filter(java.util.Objects::nonNull)
                .map(RoleModel::getRole)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toList());
    }

    private boolean hasRoleChange(List<RoleModel> currentRoles, List<RoleModel> requestedRoles) {
        return !normalizeRoles(currentRoles).equals(normalizeRoles(requestedRoles));
    }

    private boolean canReadUser(String actorUserId, String targetUserId) {
        if (actorUserId == null || actorUserId.isBlank() || targetUserId == null || targetUserId.isBlank()) {
            return false;
        }
        if (actorUserId.equals(targetUserId)) {
            return true;
        }
        boolean admin = userServiceBean.isAdmin(actorUserId);
        if (!admin) {
            return false;
        }
        String actorFacility = getRemoteFacility(actorUserId);
        String targetFacility = getRemoteFacility(targetUserId);
        return actorFacility != null && actorFacility.equals(targetFacility);
    }

    private Set<String> normalizeRoles(List<RoleModel> roles) {
        Set<String> normalized = new HashSet<>();
        if (roles == null) {
            return normalized;
        }
        for (RoleModel role : roles) {
            if (role == null || role.getRole() == null) {
                continue;
            }
            String value = role.getRole().trim().toLowerCase(java.util.Locale.ROOT);
            if (!value.isEmpty()) {
                normalized.add(value);
            }
        }
        return normalized;
    }
}
