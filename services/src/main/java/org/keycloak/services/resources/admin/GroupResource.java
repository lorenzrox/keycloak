/*
 * Copyright 2016 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.keycloak.services.resources.admin;

import org.jboss.resteasy.annotations.cache.NoCache;
import javax.ws.rs.NotFoundException;
import org.keycloak.common.util.ObjectUtil;
import org.keycloak.events.admin.OperationType;
import org.keycloak.events.admin.ResourceType;
import org.keycloak.models.Constants;
import org.keycloak.models.GroupModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.utils.KeycloakModelUtils;
import org.keycloak.models.utils.ModelToRepresentation;
import org.keycloak.representations.idm.GroupRepresentation;
import org.keycloak.representations.idm.ManagementPermissionReference;
import org.keycloak.representations.idm.UserRepresentation;
import org.keycloak.services.ErrorResponse;
import org.keycloak.services.Urls;
import org.keycloak.services.resources.admin.permissions.AdminPermissionEvaluator;
import org.keycloak.services.resources.admin.permissions.AdminPermissionManagement;
import org.keycloak.services.resources.admin.permissions.AdminPermissions;

import javax.ws.rs.Consumes;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.net.URI;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import javax.ws.rs.PathParam;
import org.keycloak.models.UserModel;
import org.keycloak.services.ForbiddenException;
import org.keycloak.utils.SearchQueryUtils;

/**
 * @resource Groups
 * @author Bill Burke
 */
public class GroupResource {

    private static final String SEARCH_ID_PARAMETER = "id:";

    private final RealmModel realm;
    private final KeycloakSession session;
    private final AdminPermissionEvaluator auth;
    private final AdminEventBuilder adminEvent;
    private final GroupModel group;

    public GroupResource(RealmModel realm, GroupModel group, KeycloakSession session, AdminPermissionEvaluator auth, AdminEventBuilder adminEvent) {
        this.realm = realm;
        this.session = session;
        this.auth = auth;
        this.adminEvent = adminEvent.resource(ResourceType.GROUP);
        this.group = group;
    }

    /**
     *
     *
     * @return
     */
    @GET
    @NoCache
    @Produces(MediaType.APPLICATION_JSON)
    public GroupRepresentation getGroup() {
        this.auth.groups().requireView(group);

        GroupRepresentation rep = ModelToRepresentation.toGroupHierarchy(group, true);

        rep.setAccess(auth.groups().getAccess(group));

        return rep;
    }

    /**
     * Update group, ignores subgroups.
     *
     * @param rep
     */
    @PUT
    @Consumes(MediaType.APPLICATION_JSON)
    public Response updateGroup(GroupRepresentation rep) {
        this.auth.groups().requireManage(group);

        String groupName = rep.getName();
        if (ObjectUtil.isBlank(groupName)) {
            return ErrorResponse.error("Group name is missing", Response.Status.BAD_REQUEST);
        }

        if (!Objects.equals(groupName, group.getName())) {
            boolean exists = siblings().filter(s -> !Objects.equals(s.getId(), group.getId()))
                    .anyMatch(s -> Objects.equals(s.getName(), groupName));
            if (exists) {
                return ErrorResponse.exists("Sibling group named '" + groupName + "' already exists.");
            }
        }
        
        updateGroup(rep, group, realm, session);
        adminEvent.operation(OperationType.UPDATE).resourcePath(session.getContext().getUri()).representation(rep).success();

        return Response.noContent().build();
    }

    private Stream<GroupModel> siblings() {
        if (group.getParentId() == null) {
            return realm.getTopLevelGroupsStream();
        } else {
            return group.getParent().getSubGroupsStream();
        }
    }

    @DELETE
    public void deleteGroup() {
        this.auth.groups().requireManage(group);

        realm.removeGroup(group);
        adminEvent.operation(OperationType.DELETE).resourcePath(session.getContext().getUri()).success();
    }

    /**
     * Get group children. Only name and ids are returned.
     *
     * @return
     */
    @GET
    @Path("children")
    @NoCache
    @Produces(MediaType.APPLICATION_JSON)
    public Stream<GroupRepresentation> getChildren(@QueryParam("search") String search,
            @QueryParam("first") Integer firstResult,
            @QueryParam("max") Integer maxResults,
            @QueryParam("briefRepresentation") @DefaultValue("true") boolean briefRepresentation) {
        auth.groups().requireView(group);

        if (Objects.nonNull(search)) {
            return ModelToRepresentation.searchForSubGroupByName(group, !briefRepresentation, search.trim(), firstResult, maxResults);
        } else if (Objects.nonNull(firstResult) && Objects.nonNull(maxResults)) {
            return ModelToRepresentation.toSubGroupHierarchy(group, !briefRepresentation, firstResult, maxResults);
        } else {
            return ModelToRepresentation.toSubGroupHierarchy(group, !briefRepresentation);
        }
    }

    @GET
    @NoCache
    @Path("children/count")
    @Produces(MediaType.APPLICATION_JSON)
    public Long getChildrenCount(@QueryParam("search") String search) {
        auth.groups().requireView(group);

        if (Objects.nonNull(search)) {
            return group.getSubGroupsCountByNameContaining(search);
        } else {
            return group.getSubGroupsCount();
        }
    }

    /**
     * Set or create child. This will just set the parent if it exists. Create
     * it and set the parent if the group doesn't exist.
     *
     * @param rep
     */
    @POST
    @Path("children")
    @NoCache
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    public Response addChild(GroupRepresentation rep) {
        this.auth.groups().requireManage(group);

        String groupName = rep.getName();
        if (ObjectUtil.isBlank(groupName)) {
            return ErrorResponse.error("Group name is missing", Response.Status.BAD_REQUEST);
        }

        Response.ResponseBuilder builder = Response.status(204);
        GroupModel child = null;
        if (rep.getId() != null) {
            child = realm.getGroupById(rep.getId());
            if (child == null) {
                throw new NotFoundException("Could not find child by id");
            }
            realm.moveGroup(child, group);
            adminEvent.operation(OperationType.UPDATE);
        } else {
            child = realm.createGroup(groupName, group);
            updateGroup(rep, child, realm, session);
            URI uri = session.getContext().getUri().getBaseUriBuilder()
                    .path(AdminRoot.class)
                    .path(AdminRoot.class, "getRealmsAdmin")
                    .path(RealmsAdminResource.class, "getRealmAdmin")
                    .path(RealmAdminResource.class, "getGroups")
                    .path(GroupsResource.class, "getGroupById")
                    .build(realm.getName(), child.getId());
            builder.status(201).location(uri);
            rep.setId(child.getId());
            adminEvent.operation(OperationType.CREATE);

        }
        adminEvent.resourcePath(session.getContext().getUri()).representation(rep).success();

        GroupRepresentation childRep = ModelToRepresentation.toGroupHierarchy(child, true);
        return builder.type(MediaType.APPLICATION_JSON_TYPE).entity(childRep).build();
    }

    public static void updateGroup(GroupRepresentation rep, GroupModel model, RealmModel realm, KeycloakSession session) {
        String newName = rep.getName();
        if (newName != null) {
            String existingName = model.getName();
            if (!newName.equals(existingName)) {
                String previousPath = KeycloakModelUtils.buildGroupPath(model);

                model.setName(newName);

                String newPath = KeycloakModelUtils.buildGroupPath(model);

                GroupModel.GroupPathChangeEvent event =
                        new GroupModel.GroupPathChangeEvent() {
                            @Override
                            public RealmModel getRealm() {
                                return realm;
                            }

                            @Override
                            public String getNewPath() {
                                return newPath;
                            }

                            @Override
                            public String getPreviousPath() {
                                return previousPath;
                            }

                            @Override
                            public KeycloakSession getKeycloakSession() {
                                return session;
                            }
                        };
                session.getKeycloakSessionFactory().publish(event);
            }
        }

        if (rep.getAttributes() != null) {
            Set<String> attrsToRemove = new HashSet<>(model.getAttributes().keySet());
            attrsToRemove.removeAll(rep.getAttributes().keySet());
            for (Map.Entry<String, List<String>> attr : rep.getAttributes().entrySet()) {
                model.setAttribute(attr.getKey(), attr.getValue());
            }

            for (String attr : attrsToRemove) {
                model.removeAttribute(attr);
            }
        }
    }

    @Path("role-mappings")
    public RoleMapperResource getRoleMappings() {
        AdminPermissionEvaluator.RequirePermissionCheck manageCheck = () -> auth.groups().requireManage(group);
        AdminPermissionEvaluator.RequirePermissionCheck viewCheck = () -> auth.groups().requireView(group);
        return new RoleMapperResource(session, auth, group, adminEvent, manageCheck, viewCheck);

    }

    /**
     * Get representation of the user
     *
     * @param id User id
     * @return
     */
    @GET
    @NoCache
    @Path("members/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public UserRepresentation getMember(final @PathParam("id") String id) {
        this.auth.groups().requireViewMembers(group);

        UserModel user = session.users().getUserById(realm, id);
        if (user.isMemberOf(group)) {
            return ModelToRepresentation.toRepresentation(session, realm, user);
        } else if (auth.users().canQuery()) {
            throw new NotFoundException("User not found");
        } else {
            throw new ForbiddenException();
        }
    }

    /**
     * Get users
     *
     * Returns a stream of users, filtered according to query parameters
     *
     * @param firstResult Pagination offset
     * @param maxResults Maximum results size (defaults to 100)
     * @param briefRepresentation Only return basic information (only guaranteed
     * to return id, username, created, first and last name, email, enabled
     * state, email verification state, federation link, and access. Note that
     * it means that namely user attributes, required actions, and not before
     * are not returned.)
     * @return a non-null {@code Stream} of users
     */
    @GET
    @NoCache
    @Path("members")
    @Produces(MediaType.APPLICATION_JSON)
    public Stream<UserRepresentation> getMembers(@QueryParam("search") String search,
            @QueryParam("lastName") String last,
            @QueryParam("firstName") String first,
            @QueryParam("email") String email,
            @QueryParam("emailVerified") Boolean emailVerified,
            @QueryParam("idpAlias") String idpAlias,
            @QueryParam("idpUserId") String idpUserId,
            @QueryParam("username") String username,
            @QueryParam("first") Integer firstResult,
            @QueryParam("max") Integer maxResults,
            @QueryParam("enabled") Boolean enabled,
            @QueryParam("briefRepresentation") Boolean briefRepresentation,
            @QueryParam("exact") Boolean exact,
            @QueryParam("q") String searchQuery) {
        this.auth.groups().requireViewMembers(group);

        firstResult = firstResult != null ? firstResult : 0;
        maxResults = maxResults != null ? maxResults : Constants.DEFAULT_MAX_RESULTS;

        Map<String, String> searchAttributes = searchQuery == null
                ? Collections.emptyMap()
                : SearchQueryUtils.getFields(searchQuery);

        session.setAttribute(UserModel.GROUPS, Collections.singleton(group.getId()));

        Stream<UserModel> userModels = Stream.empty();

        if (search != null) {
            if (search.startsWith(SEARCH_ID_PARAMETER)) {
                UserModel userModel = session.users().getUserById(realm,
                        search.substring(SEARCH_ID_PARAMETER.length()).trim());
                if (userModel != null) {
                    userModels = Stream.of(userModel);
                }
            } else {
                Map<String, String> attributes = new HashMap<>();
                attributes.put(UserModel.SEARCH, search.trim());
                if (enabled != null) {
                    attributes.put(UserModel.ENABLED, enabled.toString());
                }

                userModels = session.users().searchForUserStream(realm, attributes, firstResult, maxResults);;
            }
        } else if (last != null || first != null || email != null || username != null || emailVerified != null
                || idpAlias != null || idpUserId != null || enabled != null || exact != null
                || !searchAttributes.isEmpty()) {
            Map<String, String> attributes = new HashMap<>();
            if (last != null) {
                attributes.put(UserModel.LAST_NAME, last);
            }
            if (first != null) {
                attributes.put(UserModel.FIRST_NAME, first);
            }
            if (email != null) {
                attributes.put(UserModel.EMAIL, email);
            }
            if (username != null) {
                attributes.put(UserModel.USERNAME, username);
            }
            if (emailVerified != null) {
                attributes.put(UserModel.EMAIL_VERIFIED, emailVerified.toString());
            }
            if (idpAlias != null) {
                attributes.put(UserModel.IDP_ALIAS, idpAlias);
            }
            if (idpUserId != null) {
                attributes.put(UserModel.IDP_USER_ID, idpUserId);
            }
            if (enabled != null) {
                attributes.put(UserModel.ENABLED, enabled.toString());
            }
            if (exact != null) {
                attributes.put(UserModel.EXACT, exact.toString());
            }

            attributes.putAll(searchAttributes);

            userModels = session.users().searchForUserStream(realm, attributes, firstResult, maxResults);;
        } else {
            userModels = session.users().getGroupMembersStream(realm, group, firstResult, maxResults);
        }

        if (briefRepresentation != null && briefRepresentation) {
            return userModels.map(user -> ModelToRepresentation.toBriefRepresentation(user));
        } else {
            return userModels.map(user -> ModelToRepresentation.toRepresentation(session, realm, user));
        }
    }

    @GET
    @NoCache
    @Path("members/count")
    @Produces(MediaType.APPLICATION_JSON)
    public Long getMemberCount(@QueryParam("search") String search,
            @QueryParam("lastName") String last,
            @QueryParam("firstName") String first,
            @QueryParam("email") String email,
            @QueryParam("emailVerified") Boolean emailVerified,
            @QueryParam("username") String username,
            @QueryParam("enabled") Boolean enabled) {
        this.auth.groups().requireViewMembers(group);

        if (search != null) {
            if (search.startsWith(SEARCH_ID_PARAMETER)) {
                UserModel userModel = session.users().getUserById(realm, search.substring(SEARCH_ID_PARAMETER.length()).trim());
                return userModel != null && userModel.isMemberOf(group) ? 1L : 0L;
            } else {
                return Long.valueOf(session.users().getUsersCount(realm, search.trim(), Collections.singleton(group.getId())));
            }
        } else if (last != null || first != null || email != null || username != null || emailVerified != null || enabled != null) {
            Map<String, String> parameters = new HashMap<>();
            if (last != null) {
                parameters.put(UserModel.LAST_NAME, last);
            }
            if (first != null) {
                parameters.put(UserModel.FIRST_NAME, first);
            }
            if (email != null) {
                parameters.put(UserModel.EMAIL, email);
            }
            if (username != null) {
                parameters.put(UserModel.USERNAME, username);
            }
            if (emailVerified != null) {
                parameters.put(UserModel.EMAIL_VERIFIED, emailVerified.toString());
            }
            if (enabled != null) {
                parameters.put(UserModel.ENABLED, enabled.toString());
            }

            return Long.valueOf(session.users().getUsersCount(realm, parameters, Collections.singleton(group.getId())));
        } else {
            return Long.valueOf(session.users().getUsersCount(realm, Collections.singleton(group.getId())));
        }
    }

    /**
     * Return object stating whether client Authorization permissions have been
     * initialized or not and a reference
     *
     * @return
     */
    @Path("management/permissions")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @NoCache
    public ManagementPermissionReference getManagementPermissions() {
        auth.groups().requireView(group);

        AdminPermissionManagement permissions = AdminPermissions.management(session, realm);
        if (!permissions.groups().isPermissionsEnabled(group)) {
            return new ManagementPermissionReference();
        }
        return toMgmtRef(group, permissions);
    }

    public static ManagementPermissionReference toMgmtRef(GroupModel group, AdminPermissionManagement permissions) {
        ManagementPermissionReference ref = new ManagementPermissionReference();
        ref.setEnabled(true);
        ref.setResource(permissions.groups().resource(group).getId());
        ref.setScopePermissions(permissions.groups().getPermissions(group));
        return ref;
    }

    /**
     * Return object stating whether client Authorization permissions have been
     * initialized or not and a reference
     *
     *
     * @return initialized manage permissions reference
     */
    @Path("management/permissions")
    @PUT
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    @NoCache
    public ManagementPermissionReference setManagementPermissionsEnabled(ManagementPermissionReference ref) {
        auth.groups().requireManage(group);
        AdminPermissionManagement permissions = AdminPermissions.management(session, realm);
        permissions.groups().setPermissionsEnabled(group, ref.isEnabled());
        if (ref.isEnabled()) {
            return toMgmtRef(group, permissions);
        } else {
            return new ManagementPermissionReference();
        }
    }

}
