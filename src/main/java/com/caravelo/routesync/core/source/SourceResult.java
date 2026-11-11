package com.caravelo.routesync.core.source;

import java.util.List;
import java.util.Set;

import com.caravelo.routesync.core.route.CanonicalRoute;

public record SourceResult(String sourceType, Set<CanonicalRoute> routes, List<MappingIssue> issues) {}
