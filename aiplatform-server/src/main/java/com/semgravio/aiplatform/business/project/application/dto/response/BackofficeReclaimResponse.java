package com.semgravio.aiplatform.business.project.application.dto.response;

import java.time.LocalDateTime;

import com.semgravio.aiplatform.base.workspace.domain.enums.ContainerState;
import com.semgravio.aiplatform.base.workspace.domain.model.Operator;
import com.semgravio.aiplatform.base.workspace.domain.model.ThreeCleanCheck;

/**
 * 后台一键回收回执（#490，POST /api/backoffice/projects/{id}/reclaim 与
 * /api/backoffice/workspaces/{id}/reclaim）：删除＋清残一次完成后的对账报告——
 * 四面（项目级：容器/卷/注册表行/项目行）或三面（工作区级：容器/卷/注册表行，
 * 项目面 UNTOUCHED 如实分示）逐面 CLEAN/RESIDUE/UNKNOWN＋{@code residueFree}
 * 零残留判据（<b>UNKNOWN 不算清</b>——销毁未全成/探查未成时各面不冒充清，回执
 * 如实红、残留归收编通道）。{@code runsStopped}＝回收时有在飞 run 被级联停止
 * （承 #477 停止面；无在飞 false）。操作者两列回显即留痕落库值（append-only 行
 * 另落 {@code wsp_reclaim_actions}）。
 *
 * @param scope        回收范围：PROJECT / WORKSPACE
 * @param projectId    项目级＝回收的项目；工作区级＝所属项目引用（无所属 null——
 *                    在册失联裁决面〔#488〕与 fork）
 * @param workspaceId  回收的工作区（三清对象）
 * @param runsStopped  在飞 run 级联停止与否
 * @param container    容器面：CLEAN / RESIDUE / UNKNOWN
 * @param volume       卷面：同上
 * @param registryRow  注册表行面：同上
 * @param projectRow   项目行面：项目级 CLEAN / RESIDUE / UNKNOWN；工作区级
 *                     UNTOUCHED（不动项目面——非清非残留）
 * @param residueFree  零残留判据（scope 内受检面全 CLEAN；任一面 RESIDUE/UNKNOWN
 *                     即 false）
 * @param operatorId   操作者标识（admin 侧；缺透传头为 null——落空口径）
 * @param operatorName 操作者昵称（同上）
 * @param reclaimedAt  回收时刻
 */
public record BackofficeReclaimResponse(
        String scope,
        String projectId,
        String workspaceId,
        boolean runsStopped,
        String container,
        String volume,
        String registryRow,
        String projectRow,
        boolean residueFree,
        String operatorId,
        String operatorName,
        LocalDateTime reclaimedAt) {

    /** 面态：清（回收后已不在）。 */
    private static final String CLEAN = "CLEAN";

    /** 面态：残留（仍在——归收编通道，回执红不虚报）。 */
    private static final String RESIDUE = "RESIDUE";

    /** 面态：状态未知（销毁未全成/探查未成——不冒充清，回执红）。 */
    private static final String UNKNOWN = "UNKNOWN";

    /** 面态：未触碰（工作区级回收的项目面——单台三清不动项目行）。 */
    private static final String UNTOUCHED = "UNTOUCHED";

    /** 项目级回执：四面事实（删除级联内核事实＋销毁内核三清）。 */
    public static BackofficeReclaimResponse projectOf(Long projectId, Long workspaceId,
            boolean runsStopped, ThreeCleanCheck check, boolean projectRowClean,
            Operator operator, LocalDateTime reclaimedAt) {
        return new BackofficeReclaimResponse("PROJECT", projectId.toString(),
                workspaceId.toString(), runsStopped,
                containerFace(check), presenceFace(check.probed(), check.volumePresent()),
                presenceFace(check.probed(), check.registryRowPresent()),
                projectRowClean ? CLEAN : RESIDUE,
                check.allClean() && projectRowClean,
                operator != null ? operator.id() : null,
                operator != null ? operator.name() : null,
                reclaimedAt);
    }

    /** 工作区级回执：三面事实＋项目面 UNTOUCHED（所属项目引用可空）。 */
    public static BackofficeReclaimResponse workspaceOf(Long workspaceId, Long projectId,
            boolean runsStopped, ThreeCleanCheck check, Operator operator,
            LocalDateTime reclaimedAt) {
        return new BackofficeReclaimResponse("WORKSPACE",
                projectId != null ? projectId.toString() : null,
                workspaceId.toString(), runsStopped,
                containerFace(check), presenceFace(check.probed(), check.volumePresent()),
                presenceFace(check.probed(), check.registryRowPresent()),
                UNTOUCHED,
                check.allClean(),
                operator != null ? operator.id() : null,
                operator != null ? operator.name() : null,
                reclaimedAt);
    }

    /** 容器面（四态直判压三态：ABSENT＝清，UNKNOWN＝未知，其余＝残留）。 */
    private static String containerFace(ThreeCleanCheck check) {
        if (!check.probed() || check.container() == ContainerState.UNKNOWN) {
            return UNKNOWN;
        }
        return check.container() == ContainerState.ABSENT ? CLEAN : RESIDUE;
    }

    /** 在否面（卷/注册行）：未探＝未知，在＝残留，不在＝清。 */
    private static String presenceFace(boolean probed, boolean present) {
        if (!probed) {
            return UNKNOWN;
        }
        return present ? RESIDUE : CLEAN;
    }
}
