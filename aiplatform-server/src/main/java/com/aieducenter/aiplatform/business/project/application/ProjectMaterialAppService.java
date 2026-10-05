package com.aieducenter.aiplatform.business.project.application;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.cartisan.core.exception.ApplicationException;
import com.cartisan.core.exception.BaseCodeMessage;
import com.cartisan.data.jpa.id.TsidGenerator;

import com.aieducenter.aiplatform.base.workspace.application.WorkspaceLifecycleAppService;
import com.aieducenter.aiplatform.base.workspace.application.dto.command.WorkspaceExecCommand;
import com.aieducenter.aiplatform.base.workspace.application.dto.response.ExecResultResponse;
import com.aieducenter.aiplatform.base.workspace.domain.error.WorkspaceMessage;
import com.aieducenter.aiplatform.business.project.application.dto.response.MaterialUploadedResponse;
import com.aieducenter.aiplatform.business.project.domain.aggregate.Project;
import com.aieducenter.aiplatform.business.project.domain.error.ProjectMessage;
import com.aieducenter.aiplatform.business.project.domain.model.ProjectMaterials;
import com.aieducenter.aiplatform.business.project.domain.repository.ProjectRepository;

/**
 * 物料上传用例（#286，ADR-0027 图片管道底座）：multipart 单文件经 stdin 灌入
 * 工作区物料目录（存储正本＝工作区容器卷，平台零文件库）——校验（五格式＋10MB
 * 如实报错不静默压缩）→ TSID 前缀落点命名（不撞名不覆盖）→ execWithStdin 落盘
 * （stat 字节回执核对）。响应 path 即随话发送的附件载荷引用（工作区路径引用、
 * 不带字节）。文件区可见性由既有文件树端点天然覆盖（materials/ 在交付面）、
 * 点看走 raw 直出（#283）、随封存保全（物料目录不进可重建缓存名单）。
 */
@Service
public class ProjectMaterialAppService {

    private final ProjectRepository projectRepository;
    private final WorkspaceLifecycleAppService workspaceLifecycleAppService;

    public ProjectMaterialAppService(ProjectRepository projectRepository,
            WorkspaceLifecycleAppService workspaceLifecycleAppService) {
        this.projectRepository = projectRepository;
        this.workspaceLifecycleAppService = workspaceLifecycleAppService;
    }

    /**
     * 上传一张图片物料落工作区物料目录。
     *
     * @throws ApplicationException PRJ_001 项目不存在；PRJ_013 已归档（归档是单向
     *                              终点，不再收新物料）；PRJ_044 格式不符（只收
     *                              png/jpg/webp/gif/svg）；PRJ_043 超 10MB 上限
     *                              （如实报错不静默压缩）；WSP_002 落盘失败/回执畸形
     */
    public MaterialUploadedResponse upload(Long projectId, MultipartFile file) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ApplicationException(ProjectMessage.PROJECT_NOT_FOUND));
        if (project.getArchivedAt() != null) {
            throw new ApplicationException(ProjectMessage.PROJECT_ALREADY_ARCHIVED);
        }
        String originalName = file.getOriginalFilename();
        if (!ProjectMaterials.isUploadableImage(originalName)) {
            throw new ApplicationException(ProjectMessage.MATERIAL_UPLOAD_FORMAT_INVALID);
        }
        if (file.getSize() > ProjectMaterials.MAX_UPLOAD_BYTES) {
            throw new ApplicationException(ProjectMessage.MATERIAL_UPLOAD_TOO_LARGE);
        }
        byte[] content = readBytes(file);
        String name = ProjectMaterials.sanitizeName(originalName);
        String path = ProjectMaterials.storedPath(
                Long.toString(TsidGenerator.newInstance().generate()), name);
        ExecResultResponse result = workspaceLifecycleAppService.execWithStdin(
                Long.toString(project.getWorkspaceId()),
                new WorkspaceExecCommand(ProjectMaterials.uploadCommand(path)), content);
        if (result.exitCode() != 0) {
            throw new ApplicationException(WorkspaceMessage.ENVIRONMENT_OPERATION_FAILED,
                    "物料写入失败: " + result.stderr());
        }
        long receipt = parseReceipt(result.stdout());
        if (receipt != content.length) {
            // 回执与写入字节数不符：防御性如实暴露（stat 成功时不可达）
            throw new ApplicationException(WorkspaceMessage.ENVIRONMENT_OPERATION_FAILED,
                    "物料写入回执畸形: " + result.stdout());
        }
        return new MaterialUploadedResponse(path, name, content.length);
    }

    /** 读上传内容（multipart 临时件读取失败是技术故障，如实 500 不伪装业务码）。 */
    private byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (Exception readFailure) {
            throw new ApplicationException(BaseCodeMessage.INTERNAL_SERVER_ERROR,
                    "读取上传内容失败: " + readFailure.getMessage());
        }
    }

    /** stat 字节回执 → long（畸形回执由调用方以回执值 != 字节数兜住）。 */
    private long parseReceipt(String stdout) {
        try {
            return Long.parseLong(stdout.trim());
        } catch (NumberFormatException malformed) {
            return -1L;
        }
    }
}
