package com.aieducenter.aiplatform.base.workspace.infrastructure.archive;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.aieducenter.aiplatform.base.workspace.application.WorkspaceProperties;
import com.aieducenter.aiplatform.base.workspace.domain.model.SealPackage;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 本地磁盘封存包存储（#172 v1）：真文件系统直测（@TempDir）——落盘/取包/清理/
 * 覆盖（确定性命名的自然结果）与不可读包的如实上抛（深度唤醒据此保持封存态）。
 */
class LocalDiskSealPackageStoreTest {

    @TempDir
    private Path dir;

    @Test
    void given_content_when_save_then_package_on_disk_with_path_and_size() throws IOException {
        LocalDiskSealPackageStore store = newStore();
        byte[] content = "tar-gz-bytes".getBytes();

        SealPackage pkg = store.save(WorkspaceId.of("42"), content);

        // 确定性命名 + 路径即入库寻址键 + 大小如实
        assertThat(pkg.path()).isEqualTo(dir.resolve("42.tar.gz").toString());
        assertThat(pkg.sizeBytes()).isEqualTo(content.length);
        assertThat(Files.readAllBytes(Path.of(pkg.path()))).isEqualTo(content);
    }

    @Test
    void given_same_workspace_when_save_again_then_old_package_overwritten() throws IOException {
        LocalDiskSealPackageStore store = newStore();
        WorkspaceId id = WorkspaceId.of("42");
        store.save(id, "first".getBytes());

        SealPackage second = store.save(id, "second-longer".getBytes());

        // 重复封存覆盖旧包：同路径、内容换新、目录内单文件（无残留）
        assertThat(Files.readAllBytes(Path.of(second.path()))).isEqualTo("second-longer".getBytes());
        try (Stream<Path> files = Files.list(dir)) {
            assertThat(files.count()).isEqualTo(1);
        }
    }

    @Test
    void given_saved_package_when_open_then_content_round_trips() {
        LocalDiskSealPackageStore store = newStore();
        SealPackage pkg = store.save(WorkspaceId.of("42"), "roundtrip".getBytes());

        assertThat(store.open(pkg.path())).isEqualTo("roundtrip".getBytes());
    }

    @Test
    void given_missing_package_when_open_then_throws() {
        LocalDiskSealPackageStore store = newStore();

        // 包不在如实抛（调用方口径：深度唤醒保持封存态待人工介入，不以空卷顶替）
        assertThatThrownBy(() -> store.open(dir.resolve("ghost.tar.gz").toString()))
                .isInstanceOf(java.io.UncheckedIOException.class);
    }

    @Test
    void given_saved_package_when_delete_then_file_gone_and_delete_again_noop() {
        LocalDiskSealPackageStore store = newStore();
        SealPackage pkg = store.save(WorkspaceId.of("42"), "to-clean".getBytes());

        store.delete(pkg.path());
        assertThat(Path.of(pkg.path())).doesNotExist();

        // 包不在为 no-op（尽力而为不抛——项目删除不被残留卡住）
        store.delete(pkg.path());
    }

    private LocalDiskSealPackageStore newStore() {
        WorkspaceProperties properties = new WorkspaceProperties();
        properties.setSealArchiveDir(dir.toString());
        return new LocalDiskSealPackageStore(properties);
    }
}
