package com.semgravio.aiplatform.base.workspace.infrastructure.docker;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.cartisan.core.exception.ApplicationException;
import com.semgravio.aiplatform.base.workspace.domain.error.WorkspaceMessage;
import com.semgravio.aiplatform.base.workspace.domain.model.ExecResult;
import com.semgravio.aiplatform.base.workspace.domain.model.WorkspaceHandle;
import com.semgravio.aiplatform.base.workspace.domain.model.WorkspaceId;
import com.semgravio.aiplatform.base.workspace.domain.model.WorkspaceNaming;
import com.semgravio.aiplatform.base.workspace.domain.port.EnvironmentBackend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 收口探针走网关＋浏览器真实头（#397，ADR-0034 防线 3）：「应用可访问」判据
 * （exposePort）与起服等待（startApp）的探针形态与红绿判据——Docker CLI 假面
 * seam（同 {@code DockerEnvironmentBackendAppStartTest} 形制）：覆写
 * {@code runCapture} 按命令形状回放探针应答，免 daemon。真实网关端到端（真
 * nginx×真探针）见 {@code PreviewGatewaySmokeTest}。
 *
 * <p>判据口径（#397 勘误后）：探针<b>必须经网关路径</b>（previewnet 网络别名
 * {@code preview-gateway}＋Host 头选路由），带浏览器真实头（Origin=本工作区预览
 * URL＋Referer＋Sec-Fetch 三件套）打 {@code /} 断言 2xx/3xx；HTML 引用
 * {@code /_next/static} 时对一个 chunk 以 crossorigin/module 形态（script/cors）
 * 复测非 403，无引用跳过（非 Next 应用零误伤）。失败语义复用既有 WSP_012 待期
 * 与超时窗，不新增机制。</p>
 */
class DockerEnvironmentBackendGatewayProbeTest {

    private static final WorkspaceId ID = WorkspaceId.of("42");
    private static final WorkspaceHandle HANDLE = WorkspaceHandle.dev(ID, "ws-42", "previewnet");

    /**
     * 假面：根探针/chunk 探针按命令形状回放（响应耗尽取末值），余命令直通成功。
     * 探针应答是 curl 的真实回执形态：根探针 stdout = body＋{@code \n}＋状态码
     * （{@code -w '\n%{http_code}'}），chunk 探针 stdout = 状态码。
     */
    private static final class ScriptedBackend extends DockerEnvironmentBackend {

        final List<ExecResult> rootResponses = new ArrayList<>();
        final List<ExecResult> chunkResponses = new ArrayList<>();
        final List<String> commands = new ArrayList<>();

        @Override
        protected ExecResult runCapture(String... cmd) {
            String joined = String.join(" ", cmd);
            commands.add(joined);
            if (isRootProbe(joined)) {
                return nextOf(rootResponses);
            }
            if (isChunkProbe(joined)) {
                return nextOf(chunkResponses);
            }
            return new ExecResult("", "", 0);
        }

        List<String> rootProbes() {
            return commands.stream().filter(DockerEnvironmentBackendGatewayProbeTest::isRootProbe).toList();
        }

        List<String> chunkProbes() {
            return commands.stream().filter(DockerEnvironmentBackendGatewayProbeTest::isChunkProbe).toList();
        }

        private static ExecResult nextOf(List<ExecResult> responses) {
            if (responses.size() > 1) {
                return responses.remove(0);
            }
            return responses.isEmpty() ? new ExecResult("", "", 1) : responses.get(0);
        }
    }

    /** 根探针：打网关地址的 {@code /}（无 /_next 路径段）。 */
    private static boolean isRootProbe(String command) {
        return command.contains("curl") && command.contains("http://"
                + WorkspaceNaming.PREVIEW_GATEWAY_ALIAS + "/")
                && !command.contains("/_next/");
    }

    /** chunk 复测探针：打网关地址的 /_next/ 路径。 */
    private static boolean isChunkProbe(String command) {
        return command.contains("curl") && command.contains("http://"
                + WorkspaceNaming.PREVIEW_GATEWAY_ALIAS + "/_next/");
    }

    private static ExecResult rootResponse(String body, String status) {
        return new ExecResult(body + "\n" + status, "", 0);
    }

    private static ExecResult statusResponse(String status) {
        return new ExecResult(status, "", 0);
    }

    @Test
    void given_plain_html_when_expose_port_then_probe_via_gateway_with_browser_headers_no_chunk_retest() {
        ScriptedBackend backend = new ScriptedBackend();
        backend.rootResponses.add(rootResponse("<html><body>plain app</body></html>", "200"));

        URI url = backend.exposePort(HANDLE, EnvironmentBackend.DEV_APP_CONTAINER_PORT);

        assertThat(url.toString()).isEqualTo("http://42.localhost/");
        // 探针恰一发（根），且形态是浏览器真实头：经网关别名寻址、Host 选路由、
        // Origin/Referer=本工作区预览 URL、Sec-Fetch 导航形态（document/navigate）
        assertThat(backend.rootProbes()).hasSize(1);
        String probe = backend.rootProbes().get(0);
        assertThat(probe)
                .contains("http://" + WorkspaceNaming.PREVIEW_GATEWAY_ALIAS + "/")
                .contains("-H 'Host: 42.localhost'")
                .contains("-H 'Origin: http://42.localhost'")
                .contains("-H 'Referer: http://42.localhost/'")
                .contains("Sec-Fetch-Dest: document")
                .contains("Sec-Fetch-Mode: navigate")
                .contains("Sec-Fetch-Site: same-origin")
                .contains("User-Agent: Mozilla/5.0");
        // 非 Next 应用（HTML 无 /_next/static 引用）：跳过 chunk 复测，零误伤
        assertThat(backend.chunkProbes()).isEmpty();
    }

    @Test
    void given_next_html_when_expose_port_then_chunk_retested_with_module_load_shape() {
        ScriptedBackend backend = new ScriptedBackend();
        backend.rootResponses.add(rootResponse(
                "<html><head><script src=\"/_next/static/chunks/webpack.js\"></script></head></html>",
                "200"));
        backend.chunkResponses.add(statusResponse("200"));

        URI url = backend.exposePort(HANDLE, EnvironmentBackend.DEV_APP_CONTAINER_PORT);

        assertThat(url.toString()).isEqualTo("http://42.localhost/");
        // chunk 复测生效：对 HTML 引用的首个 /_next/static 资产、以 crossorigin/module
        // 加载形态（script/cors——恰是 Next dev 跨源闸门拦的请求形态）带 Origin 复测
        assertThat(backend.chunkProbes()).hasSize(1);
        assertThat(backend.chunkProbes().get(0))
                .contains("http://" + WorkspaceNaming.PREVIEW_GATEWAY_ALIAS
                        + "/_next/static/chunks/webpack.js")
                .contains("-H 'Host: 42.localhost'")
                .contains("-H 'Origin: http://42.localhost'")
                .contains("Sec-Fetch-Dest: script")
                .contains("Sec-Fetch-Mode: cors");
    }

    @Test
    void given_chunk_gated_403_when_expose_port_then_pending_not_serving() {
        ScriptedBackend backend = new ScriptedBackend();
        backend.rootResponses.add(rootResponse(
                "<html><script src=\"/_next/static/chunks/app.js\"></script></html>", "200"));
        backend.chunkResponses.add(statusResponse("403"));

        // 闸门形态说真话（#392 起因场景）：根 200 而 chunk 403＝浏览器白屏，
        // 收口判据如实为红（WSP_012 待期），不再「探针 200、真浏览器 403」漏过
        assertThatThrownBy(() -> backend.exposePort(HANDLE, EnvironmentBackend.DEV_APP_CONTAINER_PORT))
                .isInstanceOf(ApplicationException.class)
                .extracting("codeMessage")
                .isEqualTo(WorkspaceMessage.PREVIEW_NOT_SERVING);
    }

    @Test
    void given_root_403_when_expose_port_then_pending_without_chunk_retest() {
        ScriptedBackend backend = new ScriptedBackend();
        backend.rootResponses.add(rootResponse(
                "<html><script src=\"/_next/static/chunks/app.js\"></script></html>", "403"));

        assertThatThrownBy(() -> backend.exposePort(HANDLE, EnvironmentBackend.DEV_APP_CONTAINER_PORT))
                .isInstanceOf(ApplicationException.class)
                .extracting("codeMessage")
                .isEqualTo(WorkspaceMessage.PREVIEW_NOT_SERVING);
        // 根已红：chunk 复测不发（首步不过无复测面）
        assertThat(backend.chunkProbes()).isEmpty();
    }

    @Test
    void given_upstream_down_502_when_expose_port_then_pending() {
        ScriptedBackend backend = new ScriptedBackend();
        // 网关在而上游不在：网关回 5xx 自恢复提示页——对浏览器与探针同为不可访问
        backend.rootResponses.add(rootResponse("<html>系统暂不可用</html>", "502"));

        assertThatThrownBy(() -> backend.exposePort(HANDLE, EnvironmentBackend.DEV_APP_CONTAINER_PORT))
                .isInstanceOf(ApplicationException.class)
                .extracting("codeMessage")
                .isEqualTo(WorkspaceMessage.PREVIEW_NOT_SERVING);
    }

    @Test
    void given_gateway_unreachable_when_expose_port_then_pending_not_false_green() {
        ScriptedBackend backend = new ScriptedBackend();
        // 网关不可达（别名缺席/容器不在）：curl 连不上（退出码 6/7、无状态码回执）
        backend.rootResponses.add(new ExecResult("", "curl: (7) Failed to connect", 7));

        assertThatThrownBy(() -> backend.exposePort(HANDLE, EnvironmentBackend.DEV_APP_CONTAINER_PORT))
                .isInstanceOf(ApplicationException.class)
                .extracting("codeMessage")
                .isEqualTo(WorkspaceMessage.PREVIEW_NOT_SERVING);
    }

    @Test
    void given_root_redirect_3xx_when_expose_port_then_green() {
        ScriptedBackend backend = new ScriptedBackend();
        // 2xx/3xx 均可（#397 判据）：应用自有跳转（登录墙等 302/307）不是不可访问
        backend.rootResponses.add(rootResponse("", "307"));

        URI url = backend.exposePort(HANDLE, EnvironmentBackend.DEV_APP_CONTAINER_PORT);

        assertThat(url.toString()).isEqualTo("http://42.localhost/");
        assertThat(backend.chunkProbes()).isEmpty();
    }
}
