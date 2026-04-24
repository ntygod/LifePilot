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

    @Test
    void name_长度刚好64_构造成功() {
        String boundary = "x".repeat(64);
        Instant now = Instant.now();
        Project p = new Project("id", boundary, "", ProjectIsolation.ISOLATED, "space-1", now, now);
        assertEquals(boundary, p.name());
    }

    @Test
    void instructions_长度刚好1000_构造成功() {
        String boundary = "x".repeat(1000);
        Instant now = Instant.now();
        Project p = new Project("id", "项目", boundary, ProjectIsolation.ISOLATED, "space-1", now, now);
        assertEquals(boundary, p.instructions());
    }

    @Test
    void instructions_为null_规范化为空字符串() {
        Instant now = Instant.now();
        Project p = new Project("id", "项目", null, ProjectIsolation.ISOLATED, "space-1", now, now);
        assertEquals("", p.instructions());
    }

    @Test
    void isolation_为null_抛异常() {
        Instant now = Instant.now();
        assertThrows(IllegalArgumentException.class, () ->
            new Project("id", "项目", "", null, "space-1", now, now));
    }

    @Test
    void memorySpaceId_为空或blank_抛异常() {
        Instant now = Instant.now();
        assertThrows(IllegalArgumentException.class, () ->
            new Project("id", "项目", "", ProjectIsolation.ISOLATED, null, now, now));
        assertThrows(IllegalArgumentException.class, () ->
            new Project("id", "项目", "", ProjectIsolation.ISOLATED, "  ", now, now));
    }
}
