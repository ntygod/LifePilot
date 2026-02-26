package com.lifepilot.sync.engine;

import com.lifepilot.sync.model.LocalChangeSet;
import com.lifepilot.sync.model.RemoteChangeSet;
import com.lifepilot.sync.model.SyncConflict;

import java.util.List;

/**
 * 冲突解决结果 record，包含经冲突解决后的远程变更、本地变更和未解决冲突。
 *
 * @param resolvedRemoteChanges 经冲突解决后需要应用到本地的远程变更
 * @param resolvedLocalChanges  经冲突解决后需要推送到远程的本地变更
 * @param unresolvedConflicts   USER_CONFIRM 策略下未解决的冲突列表
 * @author zsg
 * @since 2026-02-26
 */
public record ConflictResolution(
        List<RemoteChangeSet.RemoteEntity> resolvedRemoteChanges,
        List<LocalChangeSet.LocalEntity> resolvedLocalChanges,
        List<SyncConflict> unresolvedConflicts
) {

    /**
     * 创建 ConflictResolution 实例，对列表进行防御性拷贝。
     */
    public ConflictResolution {
        resolvedRemoteChanges = List.copyOf(resolvedRemoteChanges);
        resolvedLocalChanges = List.copyOf(resolvedLocalChanges);
        unresolvedConflicts = List.copyOf(unresolvedConflicts);
    }
}
