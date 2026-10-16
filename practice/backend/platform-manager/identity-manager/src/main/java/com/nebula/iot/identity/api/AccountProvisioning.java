package com.nebula.iot.identity.api;

/** Creates a login account without granting roles or tenant membership. Joins the caller's transaction. */
public interface AccountProvisioning {
    AccessViews.User createUser(String actor, String username, String password, String tenantId);
}