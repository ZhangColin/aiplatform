package com.aieducenter.aiplatform.base.skills.application;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cartisan.core.exception.ApplicationException;

import com.aieducenter.aiplatform.base.skills.application.dto.response.BackofficeSkillDetailResponse;
import com.aieducenter.aiplatform.base.skills.application.dto.response.BackofficeSkillSummaryResponse;
import com.aieducenter.aiplatform.base.skills.domain.error.SkillMessage;
import com.aieducenter.aiplatform.base.skills.domain.model.BuiltinSkill;
import com.aieducenter.aiplatform.base.skills.domain.model.SkillRecord;
import com.aieducenter.aiplatform.base.skills.domain.port.BuiltinSkillCatalog;
import com.aieducenter.aiplatform.base.skills.domain.repository.SkillStore;
import com.aieducenter.aiplatform.support.Tsid;

/**
 * 后台技能库读用例（#247 技能管理域第一块）：清单（内置 classpath 合成 ∪ 库行
 * 同权呈现，空库仍非空）/ 详情（frontmatter 与正文全文可读——审核面）。寻址柄
 * 两形制在此分解：{@code builtin:<技能名>} 走内置目录、TSID 走库——id 是 opaque
 * 串的口径由本层单点定形（清单行合成与详情分解同源）。
 *
 * <p>排序服务端定死：内置在前（名称序）、安装在后（来源包、名称序——跨包同名
 * 区分呈现、同包聚簇审阅）。写口（安装/启停/卸载）属 #248，本类只读。</p>
 */
@Service
public class BackofficeSkillAppService {

    private final BuiltinSkillCatalog builtinSkillCatalog;
    private final SkillStore skillStore;

    public BackofficeSkillAppService(BuiltinSkillCatalog builtinSkillCatalog, SkillStore skillStore) {
        this.builtinSkillCatalog = builtinSkillCatalog;
        this.skillStore = skillStore;
    }

    /**
     * 技能清单：内置（名称序）在前、安装（来源包、名称序）在后；不分页——技能
     * 库是有界目录（装什么是运营决策），对齐账号清单先例。
     */
    @Transactional(readOnly = true)
    public List<BackofficeSkillSummaryResponse> skills() {
        List<BackofficeSkillSummaryResponse> items = new ArrayList<>(
                builtinSkillCatalog.findAll().stream()
                        .map(BackofficeSkillSummaryResponse::builtin).toList());
        items.addAll(skillStore.findAll().stream()
                .map(BackofficeSkillSummaryResponse::of).toList());
        return items;
    }

    /**
     * 技能详情（审核面）：元数据＋frontmatter 全量＋正文全文。
     *
     * @throws ApplicationException SKL_001 技能不存在（未寻址内置名/TSID、畸形柄同语义）
     */
    @Transactional(readOnly = true)
    public BackofficeSkillDetailResponse detail(String id) {
        if (id.startsWith(BackofficeSkillSummaryResponse.BUILTIN_ID_PREFIX)) {
            BuiltinSkill skill = builtinSkillCatalog.findByName(
                    id.substring(BackofficeSkillSummaryResponse.BUILTIN_ID_PREFIX.length()));
            if (skill == null) {
                throw new ApplicationException(SkillMessage.SKILL_NOT_FOUND);
            }
            return BackofficeSkillDetailResponse.builtin(skill);
        }
        SkillRecord record = skillStore.find(Tsid.resolve(id, SkillMessage.SKILL_NOT_FOUND));
        if (record == null) {
            throw new ApplicationException(SkillMessage.SKILL_NOT_FOUND);
        }
        return BackofficeSkillDetailResponse.of(record);
    }
}
