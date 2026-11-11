package com.caravelo.routesync.core.model;

/** A record dropped or corrected while mapping a source, with the SPEC rule that applied ("R3", "R9"…). */
public record MappingIssue(String rule, String message) {}
