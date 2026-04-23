package com.lifepilot.project.model;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * ProjectIsolation 枚举单元测试。
 *
 * @author zsg
 * @since 2026-04-23
 */
class ProjectIsolation_枚举测试 {

    @Test
    void 默认值为_ISOLATED() {
        assertEquals(ProjectIsolation.ISOLATED, ProjectIsolation.defaultValue());
    }

    @Test
    void fromString_支持大小写兼容() {
        assertEquals(ProjectIsolation.ISOLATED, ProjectIsolation.fromString("isolated"));
        assertEquals(ProjectIsolation.SHARED, ProjectIsolation.fromString("SHARED"));
    }

    @Test
    void fromString_非法值抛异常() {
        assertThrows(IllegalArgumentException.class, () -> ProjectIsolation.fromString("BOGUS"));
    }
}
