package com.lifepilot.a2a.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.lang.Nullable;

import java.util.List;

/**
 * A2A Artifact — Task 产出物。
 *
 * @author zsg
 * @since 2026-02-28
 */
public record A2aArtifact(
        String artifactId,
        List<A2aPart> parts,
        @Nullable @JsonInclude(JsonInclude.Include.NON_NULL) String name,
        @Nullable @JsonInclude(JsonInclude.Include.NON_NULL) String description
) {
    public A2aArtifact {
        parts = parts == null ? List.of() : List.copyOf(parts);
    }
}
