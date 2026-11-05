package com.semgravio.aiplatform.base.workspace.infrastructure.docker;

import com.semgravio.aiplatform.base.workspace.domain.model.WorkspaceNaming;
import com.semgravio.aiplatform.base.workspace.domain.port.EnvironmentBackend;

/**
 * 探针命令形状谓词单源（#401 探针形态散弹止血）：探针判据演化（#45→#128→
 * #183→#397）每轮都要同步改三个测试假面（AppStartTest／SealTest／活体）里各自
 * 复制的字符串匹配——谓词收拢到本类后，再改探针形态只动 {@link DockerEnvironmentBackend}
 * 的命令构造与本类一处，假面消费谓词不再各改字符串。
 *
 * <p>URL 令牌派生自正主常量（{@link WorkspaceNaming#PREVIEW_GATEWAY_ALIAS}、
 * {@link EnvironmentBackend#DEV_APP_CONTAINER_PORT}）——别名/端口变更自动传播。
 * 命令构造本体（browserHeaders／rootProbeCommand／assetProbeCommand）单源在
 * {@link DockerEnvironmentBackend}，本类只认形状（匹配判据）。</p>
 */
public final class ProbeShape {

    /** 回环应用地址（幂等守卫探针 {@code appListeningInContainer} 的目标）。 */
    public static final String LOOPBACK_APP_URL =
            "http://localhost:" + EnvironmentBackend.DEV_APP_CONTAINER_PORT;

    /** 网关根地址（收口探针 {@code rootProbeCommand} 的目标，经别名寻址）。 */
    public static final String GATEWAY_ROOT_URL =
            "http://" + WorkspaceNaming.PREVIEW_GATEWAY_ALIAS + "/";

    /** Next 静态资产路径段（chunk 复测探针的目标前缀特征）。 */
    private static final String NEXT_ASSET_PATH = "/_next/";

    private ProbeShape() {
    }

    /**
     * 回环守卫探针：容器内单发 curl 打回环应用端口（只答「进程在不在服」，
     * 不作收口判据——#397 口径）。
     */
    public static boolean isLoopbackGuard(String command) {
        return command.contains("curl") && command.contains(LOOPBACK_APP_URL);
    }

    /**
     * 网关根探针：curl 经网关别名打 {@code /}（无 /_next 路径段——含者为 chunk
     * 复测探针）。
     */
    public static boolean isRootProbe(String command) {
        return command.contains("curl") && command.contains(GATEWAY_ROOT_URL)
                && !command.contains(NEXT_ASSET_PATH);
    }

    /** chunk 复测探针：curl 经网关别名打 /_next/ 路径（script/cors 形态）。 */
    public static boolean isChunkProbe(String command) {
        return command.contains("curl") && command.contains(GATEWAY_ROOT_URL + "_next/");
    }

    /** 网关探针（根或 chunk）：假面回放「经网关判据」的合并判据。 */
    public static boolean isGatewayProbe(String command) {
        return isRootProbe(command) || isChunkProbe(command);
    }
}
