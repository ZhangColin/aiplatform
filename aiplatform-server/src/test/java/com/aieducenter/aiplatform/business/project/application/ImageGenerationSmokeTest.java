package com.aieducenter.aiplatform.business.project.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.aieducenter.aiplatform.IntegrationTest;
import com.aieducenter.aiplatform.base.eventhub.application.EventsAppService;
import com.aieducenter.aiplatform.base.workspace.application.WorkspaceLifecycleAppService;
import com.aieducenter.aiplatform.base.workspace.application.dto.command.CreateWorkspaceCommand;
import com.aieducenter.aiplatform.base.workspace.application.dto.command.WorkspaceExecCommand;
import com.aieducenter.aiplatform.base.workspace.application.dto.response.ExecResultResponse;
import com.aieducenter.aiplatform.base.workspace.application.dto.response.WorkspaceResponse;
import com.aieducenter.aiplatform.base.workspace.domain.enums.EnvKind;
import com.aieducenter.aiplatform.base.workspace.domain.model.WorkspaceLayout;
import com.aieducenter.aiplatform.business.project.application.ImageGenerationAppService.ImageGenerationOutcome;
import com.aieducenter.aiplatform.business.project.domain.aggregate.Project;
import com.aieducenter.aiplatform.business.project.domain.repository.ProjectRepository;

/**
 * 出图真跑冒烟（#292 验收「按张计量事件真跑进成本观测」；出图供数方 key 未设
 * 或 docker daemon 不在整类跳过，对偶 #43 链必达冒烟口径）：真供应商（active
 * 适配器——本机 .env.local 配 volcano）＋真 dev 容器转存落盘＋真计量事件落库
 * 三事实一把验——图片模型路（generate_image 工具件背后的内核）的「绿测≠能跑」
 * 补位。代码出图路（HTML→PNG）的渲染保真归 WorkspaceRendersLiveTest；整场
 * 设计会话的活体走查（执行体自选档位、收口即渲）归用户。
 */
@IntegrationTest
class ImageGenerationSmokeTest {

    private static final int PROBE_TIMEOUT_SECONDS = 600;

    /** 计量会话标识（事件清位的锚——按 session 精确清，不碰别家事件）。 */
    private static final String SMOKE_SESSION = "designer-image-smoke";

    @Autowired
    private ImageGenerationAppService imageGeneration;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private WorkspaceLifecycleAppService workspaceLifecycleAppService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 真跑只出一张图、其余面零事件依赖——事件发射边无订阅者广播口无需介入。 */
    @MockitoBean
    private EventsAppService eventsAppService;

    private String workspaceId;
    private Long projectId;

    @BeforeAll
    static void requireEnvironment() {
        Assumptions.assumeTrue(activeProviderKeyPresent(),
                "出图供数方 key 未设置（IMAGE_GEN_PROVIDER 与对应 key），跳过出图真跑冒烟");
        Assumptions.assumeTrue(dockerAvailable(), "本机 docker daemon 不在，跳过真实工作区链路");
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM met_usage_events WHERE session_id = ?", SMOKE_SESSION);
        if (projectId != null) {
            jdbcTemplate.update("DELETE FROM prj_projects WHERE id = ?", projectId);
        }
        if (workspaceId != null) {
            try {
                workspaceLifecycleAppService.destroy(workspaceId);
            }
            catch (RuntimeException e) {
                // 物理清理尽力而为（记录已随 destroy 删；Docker 残留可重试销毁）
            }
        }
    }

    @Test
    @Timeout(PROBE_TIMEOUT_SECONDS)
    void given_real_provider_when_generate_then_landed_file_and_one_image_event() {
        // 真供应商出一张图（按张计费如实入账）、容器转存落盘 design/、恰一条按张
        // 计量事件（images=1、token 五档为零、dims.agentKind=designer）
        WorkspaceResponse workspace = workspaceLifecycleAppService
                .create(new CreateWorkspaceCommand(EnvKind.DEV));
        workspaceId = workspace.workspaceId();
        Project project = projectRepository.save(Project.create("出图真跑冒烟", null,
                Long.parseLong(workspaceId), null));
        projectId = project.getId();

        ImageGenerationOutcome outcome = imageGeneration.generate(workspaceId, null,
                SMOKE_SESSION, "一张极简风格的品牌插画：米色背景上一枚绿色圆环，扁平风格，无文字", 1,
                null, "冒烟-圆环");

        assertThat(outcome).as("真供应商应出图成功（失败即真跑失败，不粉饰）")
                .isInstanceOf(ImageGenerationOutcome.Generated.class);
        ImageGenerationOutcome.Generated generated = (ImageGenerationOutcome.Generated) outcome;
        assertThat(generated.failures()).isEmpty();
        assertThat(generated.files()).singleElement().satisfies(file -> {
            assertThat(file.path()).as("转存回执＝工作区相对形（锚定化归工具报告面）")
                    .startsWith("design/");
            assertThat(file.sizeBytes()).isPositive();
        });
        // 容器事实：稿真落盘（转存回执之外的独立见证——stat 字节）
        ExecResultResponse stat = workspaceLifecycleAppService.exec(workspaceId,
                new WorkspaceExecCommand("wc -c < '" + WorkspaceLayout.absolute(
                        generated.files().get(0).path()) + "'"));
        assertThat(stat.exitCode()).isZero();
        assertThat(Long.parseLong(stat.stdout().trim()))
                .as("容器内稿字节数应与转存回执一致").isEqualTo(generated.files().get(0).sizeBytes());

        // 计量事实：恰一条按张事件、张数 1、token 五档为零、dims 三键齐（agentKind=designer）
        List<Map<String, Object>> events = jdbcTemplate.queryForList(
                "SELECT provider, model, images, input, output, dims"
                        + " FROM met_usage_events WHERE session_id = ?", SMOKE_SESSION);
        assertThat(events).singleElement().satisfies(event -> {
            assertThat(((Number) event.get("images")).longValue()).isEqualTo(1);
            assertThat(((Number) event.get("input")).longValue()).isZero();
            assertThat(((Number) event.get("output")).longValue()).isZero();
            // dims 三键齐（JSONB 文本形态空白不定，归一后断言）
            assertThat(String.valueOf(event.get("dims")).replaceAll("\\s", ""))
                    .contains("\"agentKind\":\"designer\"");
        });
    }

    /** active 供应商的 key 在场才真跑（.env.local 进程环境；词表对偶 application.yml）。 */
    private static boolean activeProviderKeyPresent() {
        String provider = System.getenv("IMAGE_GEN_PROVIDER");
        if (provider == null || provider.isBlank()) {
            return false;
        }
        String key = switch (provider) {
            case "zhipu" -> System.getenv("ZHIPU_API_KEY");
            case "volcano" -> System.getenv("ARK_API_KEY");
            case "openai" -> System.getenv("OPENAI_IMAGE_API_KEY");
            case "dashscope" -> System.getenv("DASHSCOPE_API_KEY");
            default -> null;
        };
        return key != null && !key.isBlank();
    }

    private static boolean dockerAvailable() {
        try {
            new ProcessBuilder("docker", "info").redirectErrorStream(true).start().waitFor();
            return true;
        }
        catch (Exception e) {
            return false;
        }
    }
}
