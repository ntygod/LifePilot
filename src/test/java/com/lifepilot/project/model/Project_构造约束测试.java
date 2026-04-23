package com.lifepilot.project.model;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

class Project_构造约束测试 {

    @Test
    void name_非空校验() {
        Instant now = Instant.now();
        assertThrows(IllegalArgumentException.class, () ->
            new Project("id", "", "指示词", ProjectIsolation.ISOLATED, "space-1", now, now));
        assertThrows(IllegalArgumentException.class, () ->
            new Project("id", "  ", "指示词", ProjectIsolation.ISOLATED, "space-1", now, now));
    }

    @Test
    void name_超长校验() {
        String tooLong = "x".repeat(65);
        Instant now = Instant.now();
        assertThrows(IllegalArgumentException.class, () ->
            new Project("id", tooLong, "", ProjectIsolation.ISOLATED, "space-1", now, now));
    }

    @Test
    void instructions_超长校验() {
        String tooLong = "x".repeat(1001);
        Instant now = Instant.now();
        assertThrows(IllegalArgumentException.class, () ->
            new Project("id", "项目 A", tooLong, ProjectIsolation.ISOLATED, "space-1", now, now));
    }
}
