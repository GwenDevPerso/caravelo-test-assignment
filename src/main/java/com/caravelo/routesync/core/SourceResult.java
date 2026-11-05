package com.caravelo.routesync.core;

import java.util.List;
import java.util.Set;

public record SourceResult(String sourceType, Set<CanonicalRoute> routes, List<MappingIssue> issues) {}
