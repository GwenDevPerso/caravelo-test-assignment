package com.caravelo.routesync.sync;

import java.util.Set;

import com.caravelo.routesync.core.route.RouteType;

/** One change of a tenant's network; old fields are null for ADDED, new fields for REMOVED (SPEC §6.3). */
public record RouteChange(ChangeType changeType, String origin, String destination,
                          RouteType oldType, RouteType newType, Set<String> oldVia, Set<String> newVia) {}
