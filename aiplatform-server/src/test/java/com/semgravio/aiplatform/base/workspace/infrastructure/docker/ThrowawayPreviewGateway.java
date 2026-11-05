package com.semgravio.aiplatform.base.workspace.infrastructure.docker;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.semgravio.aiplatform.base.workspace.domain.model.ExecResult;
import com.semgravio.aiplatform.base.workspace.domain.model.WorkspaceNaming;

/**
 * 活体测试用一次性预览网关（#397 收口探针走网关）：dev 网关配置原样起一个
 * previewnet 容器、挂 {@code preview-gateway} 网络别名——工作区容器内的收口探针
 * （exposePort/startApp/startSnapshot）经它打到应用，与用户浏览器同观察点。
 *
 * <p><b>网关资产清单与起容参数的单源（#401，终结 #397 code-review 标记的
 * Duplicated Code）</b>：classpath 资产清单（{@link #ASSETS}）、挂载形态、
 * 别名/镜像全在本文；宿主端口映射按用例可选（本 fixture 探针在容器间走、
 * 不经宿主＝无映射；宿主侧 curl 断言的 {@code PreviewGatewaySmokeTest} 传端口
 * 消费同源 {@link #dockerRunArgs}）——网关资产增删只改一处。</p>
 *
 * <p>冒烟级（注入/剥 Origin 等网关行为本身）见 {@code PreviewGatewaySmokeTest}。
 * 无宿主端口映射；同机已有真 dev 网关（同名别名）时 Docker DNS 轮询两者，
 * 配置同源、路由同效，无断言干扰。</p>
 */
public final class ThrowawayPreviewGateway implements AutoCloseable {

    /** 网关镜像（冒烟测试同款消费，单源）。 */
    public static final String GATEWAY_IMAGE = "nginx:stable-alpine";

    /**
     * 网关资产清单（classpath 资源 → 容器挂载点）：挂载路径与块文件 alias 统一
     * 路径同构（#386：/etc/nginx/conf.d/aiplatform.assets/）。增删资产只改这里。
     */
    private record GatewayAsset(String resource, String containerPath) {}

    private static final List<GatewayAsset> ASSETS = List.of(
            new GatewayAsset("docker/gateway/nginx.conf", "/etc/nginx/nginx.conf"),
            new GatewayAsset("docker/gateway/gateway-proxy.conf", "/etc/nginx/gateway-proxy.conf"),
            new GatewayAsset("docker/gateway/gateway-inject.conf", "/etc/nginx/gateway-inject.conf"),
            new GatewayAsset("docker/gateway/unavailable.html",
                    "/etc/nginx/conf.d/aiplatform.assets/unavailable.html"),
            new GatewayAsset("docker/workspace/annotation.js",
                    "/etc/nginx/conf.d/aiplatform.assets/annotation.js"));

    private final String containerName;
    private final Path assetDir;

    private ThrowawayPreviewGateway(String containerName, Path assetDir) {
        this.containerName = containerName;
        this.assetDir = assetDir;
    }

    /**
     * 起一次性网关（幂等：同名残留先清）。配置资产从 classpath 复制到临时目录
     * （bind mount 绑 inode，复制件与仓库工作区解耦）。
     */
    public static ThrowawayPreviewGateway start(String suffix) {
        String containerName = "aiplatform-gw-test-" + suffix;
        try {
            Path dir = copyAssets(Files.createTempDirectory("aiplatform-gateway-test"));
            docker("rm", "-f", containerName);
            ExecResult started = docker(dockerRunArgs(containerName, dir)
                    .toArray(new String[0]));
            if (started.exitCode() != 0) {
                throw new IllegalStateException("一次性网关未起：" + started.stderr());
            }
            return new ThrowawayPreviewGateway(containerName, dir);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("一次性网关准备失败: " + e.getMessage(), e);
        }
    }

    /** 资产清单整份复制到目标目录（文件名取资源路径末段）。 */
    public static Path copyAssets(Path targetDir) throws Exception {
        for (GatewayAsset asset : ASSETS) {
            copyResource(asset.resource(), targetDir.resolve(fileName(asset.resource())));
        }
        return targetDir;
    }

    /**
     * 网关 {@code docker run} 参数（不含 {@code docker} 前缀，无宿主端口映射——
     * 本 fixture 的探针在容器间走、不经宿主）：进 previewnet、挂
     * {@code preview-gateway} 别名、资产清单逐项 :ro 挂载。
     */
    public static List<String> dockerRunArgs(String containerName, Path assetDir) {
        List<String> args = new ArrayList<>(List.of("run", "-d",
                "--name", containerName,
                "--network", WorkspaceNaming.PREVIEW_NETWORK,
                "--network-alias", WorkspaceNaming.PREVIEW_GATEWAY_ALIAS));
        for (GatewayAsset asset : ASSETS) {
            args.addAll(List.of("-v",
                    assetDir.resolve(fileName(asset.resource())) + ":" + asset.containerPath() + ":ro"));
        }
        args.add(GATEWAY_IMAGE);
        return args;
    }

    /** 同 {@link #dockerRunArgs(String, Path)}，再加宿主端口映射（宿主侧 curl 断言用）。 */
    public static List<String> dockerRunArgs(String containerName, Path assetDir, int hostPort) {
        List<String> args = new ArrayList<>(dockerRunArgs(containerName, assetDir));
        args.addAll(args.size() - 1, List.of("-p", hostPort + ":80"));   // 镜像名前插入
        return args;
    }

    private static String fileName(String resource) {
        return resource.substring(resource.lastIndexOf('/') + 1);
    }

    @Override
    public void close() {
        docker("rm", "-f", containerName);
        try (var walk = Files.walk(assetDir)) {
            // 资产临时目录回收（bind mount 复制件，容器删后无消费方）
            walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (Exception ignored) {
            // 尽力而为清理
        }
    }

    private static void copyResource(String resource, Path target) throws Exception {
        try (InputStream in = ThrowawayPreviewGateway.class.getClassLoader()
                .getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("找不到资源 " + resource);
            }
            Files.copy(in, target);
        }
    }

    private static ExecResult docker(String... args) {
        String[] cmd = new String[args.length + 1];
        cmd[0] = "docker";
        System.arraycopy(args, 0, cmd, 1, args.length);
        try {
            Process p = new ProcessBuilder(cmd).start();
            String out = new String(p.getInputStream().readAllBytes());
            String err = new String(p.getErrorStream().readAllBytes());
            int code = p.waitFor();
            return new ExecResult(out, err, code);
        } catch (Exception e) {
            return new ExecResult("", String.valueOf(e.getMessage()), 1);
        }
    }
}
