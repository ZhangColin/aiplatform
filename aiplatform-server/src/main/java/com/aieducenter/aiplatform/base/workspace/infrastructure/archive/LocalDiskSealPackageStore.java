package com.aieducenter.aiplatform.base.workspace.infrastructure.archive;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.stereotype.Component;

import com.cartisan.core.stereotype.Adapter;
import com.cartisan.core.stereotype.PortType;

import com.aieducenter.aiplatform.base.workspace.application.WorkspaceProperties;
import com.aieducenter.aiplatform.base.workspace.domain.model.SealPackage;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceId;
import com.aieducenter.aiplatform.base.workspace.domain.port.SealPackageStore;

import lombok.extern.slf4j.Slf4j;

/**
 * 本地磁盘封存包存储（#172 v1，ADR-0016）：{@code app.workspace.seal-archive-dir}
 * 目录下按 {@code {workspaceId}.tar.gz} 确定性命名落盘——重复封存覆盖旧包是命名
 * 的自然结果；路径即入库的寻址键（目录可配迁移：换目录属运维动作，旧行路径仍是
 * 事实）。将来换对象存储时以同形适配器替换（接口不动）。
 */
@Component
@Adapter(PortType.CLIENT)
@Slf4j
public class LocalDiskSealPackageStore implements SealPackageStore {

    private final Path archiveDir;

    public LocalDiskSealPackageStore(WorkspaceProperties properties) {
        this.archiveDir = Path.of(properties.getSealArchiveDir());
    }

    @Override
    public SealPackage save(WorkspaceId workspaceId, byte[] content) {
        Path target = archiveDir.resolve(fileName(workspaceId));
        try {
            Files.createDirectories(archiveDir);
            Files.write(target, content);
        } catch (IOException e) {
            throw new UncheckedIOException("封存包落盘失败: " + target, e);
        }
        log.info("[workspace] {} 封存包落盘：{}（{} 字节）",
                workspaceId.value(), target, content.length);
        return new SealPackage(target.toString(), content.length);
    }

    @Override
    public byte[] open(String path) {
        try {
            return Files.readAllBytes(Path.of(path));
        } catch (IOException e) {
            throw new UncheckedIOException("封存包不可读: " + path, e);
        }
    }

    @Override
    public void delete(String path) {
        try {
            Files.deleteIfExists(Path.of(path));
        } catch (IOException e) {
            // 尽力而为：清理失败只记日志（磁盘残留可手动清），不阻断删除流程
            log.warn("封存包清理失败（磁盘残留可手动清）: {}", path, e);
        }
    }

    private String fileName(WorkspaceId workspaceId) {
        return workspaceId.value() + ".tar.gz";
    }
}
