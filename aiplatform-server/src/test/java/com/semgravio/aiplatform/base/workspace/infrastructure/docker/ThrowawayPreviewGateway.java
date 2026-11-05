package com.semgravio.aiplatform.base.workspace.infrastructure.docker;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import com.semgravio.aiplatform.base.workspace.domain.model.ExecResult;
import com.semgravio.aiplatform.base.workspace.domain.model.WorkspaceNaming;

/**
 * 活体测试用一次性预览网关（#397 收口探针走网关）：dev 网关配置原样起一个
 * previewnet 容器、挂 {@code preview-gateway} 网络别名——工作区容器内的收口探针
 * （exposePort/startApp/startSnapshot）经它打到应用，与用户浏览器同观察点。
 * 冒烟级（注入/剥 Origin 等网关行为本身）见 {@code PreviewGatewaySmokeTest}
 * （其网关自起段与本类同款资产清单——网关资产增删两处同改，#386 挂载口径），
 * 本 fixture 只供「需要探针绿」的活体用例作链路底座。无宿主端口映射（探针在
 * 容器间走，不经宿主）；同机已有真 dev 网关（同名别名）时 Docker DNS 轮询两者，
 * 配置同源、路由同效，无断言干扰。
 */
public final class ThrowawayPreviewGateway implements AutoCloseable {

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
            Path dir = Files.createTempDirectory("aiplatform-gateway-test");
            copyResource("docker/gateway/nginx.conf", dir.resolve("nginx.conf"));
            copyResource("docker/gateway/gateway-proxy.conf", dir.resolve("gateway-proxy.conf"));
            copyResource("docker/gateway/gateway-inject.conf", dir.resolve("gateway-inject.conf"));
            copyResource("docker/gateway/unavailable.html", dir.resolve("unavailable.html"));
            copyResource("docker/workspace/annotation.js", dir.resolve("annotation.js"));
            docker("rm", "-f", containerName);
            // 挂载路径与块文件 alias 统一路径同构（#386：/etc/nginx/conf.d/aiplatform.assets/）
            ExecResult started = docker("run", "-d", "--name", containerName,
                    "--network", WorkspaceNaming.PREVIEW_NETWORK,
                    "--network-alias", WorkspaceNaming.PREVIEW_GATEWAY_ALIAS,
                    "-v", dir.resolve("nginx.conf") + ":/etc/nginx/nginx.conf:ro",
                    "-v", dir.resolve("gateway-proxy.conf") + ":/etc/nginx/gateway-proxy.conf:ro",
                    "-v", dir.resolve("gateway-inject.conf") + ":/etc/nginx/gateway-inject.conf:ro",
                    "-v", dir.resolve("unavailable.html")
                            + ":/etc/nginx/conf.d/aiplatform.assets/unavailable.html:ro",
                    "-v", dir.resolve("annotation.js")
                            + ":/etc/nginx/conf.d/aiplatform.assets/annotation.js:ro",
                    "nginx:stable-alpine");
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
