package com.lifepilot.sync.mapping;

import com.lifepilot.sync.model.FieldDiff;

import java.util.Map;

/**
 * 字段映射泛型接口，定义 LifePilot 本地实体与远程服务数据格式之间的双向转换。
 *
 * @param <L> 本地实体类型（如 TodoItem、ScheduleItem、HabitItem）
 * @param <R> 远程数据格式类型（如 String、Map）
 * @author zsg
 * @since 2026-02-26
 */
public interface FieldMapping<L, R> {

    /**
     * 将本地实体转换为远程数据格式。
     *
     * @param local 本地实体
     * @return 远程格式数据
     */
    R toRemote(L local);

    /**
     * 将远程数据格式转换为本地实体。
     *
     * @param remote 远程格式数据
     * @return 本地实体
     */
    L toLocal(R remote);

    /**
     * 提取冲突比较字段，返回本地与远程之间存在差异的字段映射。
     *
     * @param local  本地实体
     * @param remote 远程格式数据
     * @return 字段名 → 字段差异的映射
     */
    Map<String, FieldDiff> extractConflictFields(L local, R remote);
}
