package com.aieducenter.aiplatform.business.project.domain.repository;

import java.util.List;

import com.aieducenter.aiplatform.business.project.domain.model.AgentConfigTrace;
import com.aieducenter.aiplatform.business.project.domain.model.AgentOperationalConfig;

/**
 * 智能体运营配置存取（#251，配置表 {@code prj_agent_configs}＋留痕表
 * {@code prj_agent_config_traces}）：配置是平台资产非用户数据，无用户会话语境，
 * JdbcTemplate 原生 SQL 足矣（照 {@code base.skills} 的 {@code SkillStore}
 * 先例：接口在 domain/repository，实现在 infrastructure，不挂 Spring Data
 * 仓储）。事务由应用层 {@code @Transactional} 界定（覆盖写入与留痕追加同事务
 * 原子）。
 */
public interface AgentConfigStore {

    /**
     * 覆盖行直读（装配「库值优先、缺省回落」的读腿，每轮命令构建时查）：
     * 查无返回 null＝全回落枚举默认（调用方单点定回落语义）。
     */
    AgentOperationalConfig find(String agentKey);

    /**
     * 覆盖态整行 upsert（PUT 全量语义）：值面四件＋操作者两列同写，updated_at
     * 刷新；行首次写入即立（清空覆盖不删行——操作者列留最近写者）。
     */
    void save(AgentOperationalConfig config);

    /**
     * 变更留痕追加（append-only，仅实际变更落痕——值面未动的幂等回执不写）。
     */
    void insertTrace(AgentConfigTrace trace);

    /**
     * 留痕读面：该智能体全部留痕按时间倒序（最近先）。留痕不随任何动作删除
     * （历史事实不随配置行消失），回滚＝读旧值写回（一次新变更、留新痕）。
     */
    List<AgentConfigTrace> findTraces(String agentKey);
}
