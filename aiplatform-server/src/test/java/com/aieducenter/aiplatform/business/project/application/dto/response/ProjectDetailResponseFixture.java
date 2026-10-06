package com.aieducenter.aiplatform.business.project.application.dto.response;

import java.time.LocalDateTime;
import java.util.List;

import com.aieducenter.aiplatform.business.order.application.dto.response.OrderBriefResponse;
import com.aieducenter.aiplatform.business.project.domain.enums.GenerationState;
import com.aieducenter.aiplatform.business.project.domain.enums.ProjectEndpointType;
import com.aieducenter.aiplatform.business.project.domain.enums.ProjectStatus;
import com.aieducenter.aiplatform.business.project.domain.enums.ProjectType;

/**
 * {@link ProjectDetailResponse} 测试夹具 builder（#285 备案「再扩字段时收
 * fixture builder」——#290 再扩 designItems 触发器燃起收口：此前七处测试构造随
 * 字段扩各自散补 null，收口后扩字段只改本类）。伴随字段（{@code *Name} 族）随
 * 枚举派生，调用面不手写；缺省值＝既有各 seam 测试的共性（官网类型、系统终点、
 * 进行中、从未生成、无订单无清单），差异处链式覆盖。
 */
public final class ProjectDetailResponseFixture {

    public static Builder detailOf(String id, String name) {
        return new Builder(id, name);
    }

    private ProjectDetailResponseFixture() {
    }

    public static final class Builder {
        private final String id;
        private final String name;
        private ProjectType type = ProjectType.WEBSITE;
        private ProjectEndpointType endpointType = ProjectEndpointType.SYSTEM;
        private DesignScopeResponse designScope;
        private String workspaceId = "9000";
        private ProjectStatus status = ProjectStatus.IN_PROGRESS;
        private boolean archived;
        private final LocalDateTime createdAt = LocalDateTime.of(2026, 9, 13, 9, 0);
        private LocalDateTime prdProducedAt;
        private GenerationState generationState = GenerationState.NEVER_GENERATED;
        private OrderBriefResponse activeOrder;
        private OrderBriefResponse latestOrder;
        private List<GenerationSegmentResponse> segments;
        private List<DesignItemResponse> designItems;

        private Builder(String id, String name) {
            this.id = id;
            this.name = name;
        }

        public Builder endpointType(ProjectEndpointType endpointType) {
            this.endpointType = endpointType;
            return this;
        }

        public Builder designScope(DesignScopeResponse designScope) {
            this.designScope = designScope;
            return this;
        }

        public Builder workspaceId(String workspaceId) {
            this.workspaceId = workspaceId;
            return this;
        }

        public Builder status(ProjectStatus status) {
            this.status = status;
            return this;
        }

        public Builder archived(boolean archived) {
            this.archived = archived;
            return this;
        }

        public Builder prdProducedAt(LocalDateTime prdProducedAt) {
            this.prdProducedAt = prdProducedAt;
            return this;
        }

        public Builder generationState(GenerationState generationState) {
            this.generationState = generationState;
            return this;
        }

        public Builder segments(List<GenerationSegmentResponse> segments) {
            this.segments = segments;
            return this;
        }

        public Builder designItems(List<DesignItemResponse> designItems) {
            this.designItems = designItems;
            return this;
        }

        public ProjectDetailResponse build() {
            return new ProjectDetailResponse(id, name, type, type.getName(),
                    endpointType, endpointType.getName(), designScope, workspaceId,
                    status, status.getName(), archived, createdAt, null, prdProducedAt,
                    null, generationState, generationState.getName(), activeOrder,
                    latestOrder, segments, designItems);
        }
    }
}
