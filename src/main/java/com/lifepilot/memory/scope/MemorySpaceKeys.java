package com.lifepilot.memory.scope;

/**
 * 记忆空间 key 规范。
 *
 * @author zsg
 * @since 2026-03-27
 */
public final class MemorySpaceKeys {

    private MemorySpaceKeys() {
    }

    public static String defaultPersonal() {
        return "personal:default";
    }

    public static String defaultExperience() {
        return "agent:default";
    }

    public static String datastoreDomain(String datastoreId) {
        return "domain:datastore:" + datastoreId;
    }

    public static String knowledgeBaseDomain(String knowledgeBaseId) {
        return "domain:knowledge-base:" + knowledgeBaseId;
    }
}
