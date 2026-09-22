/**
 * Skills Context（base.skills）。
 *
 * <h3>职责</h3>
 * <ul>
 *   <li>技能库管理（#246 技能线，ADR-0021）：条目表 skl_skills（安装技能装时
 *       固化快照——来源包＋名称唯一、版本＝装时 commit）</li>
 *   <li>内置技能目录（classpath skills/ 合成，与库技能同权呈现；装配侧另读
 *       同一目录——T3 装配合成收口时归一）</li>
 *   <li>后台读面（#247：清单/详情——详情即安装审核承载面）；写口（#248：安装
 *       ＝git 仓库快照固化入库＋版本留痕、停用⇄启用、卸载＋守卫错误码定契约；
 *       指派 #249 后续票落）</li>
 * </ul>
 *
 * <h3>限界上下文</h3>
 * <p>底座技能资产：内置技能属平台发版物（classpath 只读），安装技能属运营
 * 决策物（库行）；装配合成（内置 ∪ 已指派且启用）在 business 侧既有技能装配
 * SPI 缝上做（T3）。表前缀 {@code skl_}（条目表 skl_skills），错误码前缀
 * {@code SKL_}（域码 7）。</p>
 *
 * <h3>包结构</h3>
 * <ul>
 *   <li>domain - 领域层：模型（SkillRecord 库条目读模型／BuiltinSkill 内置
 *       技能读模型／Operator 操作者／ParsedSkill＋SkillPackageSnapshot 装时
 *       解析态）、枚举（SkillStatus 状态／SkillSource 来源）、端口
 *       （BuiltinSkillCatalog 内置目录／SkillPackageFetcher 快照拉取）、
 *       SkillStore 存取接口、SKL_ 错误</li>
 *   <li>application - 应用层：BackofficeSkillAppService（#247 清单合成＋详情
 *       寻址分解——builtin: 前缀与 TSID 两形制；#248 安装/启停/卸载写口）</li>
 *   <li>infrastructure - 基础设施层：ClasspathBuiltinSkillCatalog（agentscope
 *       ClasspathSkillRepository 包装）、persistence/JdbcSkillStore
 *       （JdbcTemplate 单表读写）、git/GitSkillPackageFetcher（git CLI 子进程
 *       快照拉取——clone/rev-parse/扫描解析，超时强杀）</li>
 *   <li>endpoints - 北向接口：controller/BackofficeSkillController（#247＋#248
 *       后台技能库 REST 面，机机签名）</li>
 * </ul>
 *
 * @since 0.1.0
 */
@BoundedContext(name = "Skills", subDomain = SubDomain.SUPPORTING)
package com.aieducenter.aiplatform.base.skills;

import com.cartisan.core.stereotype.BoundedContext;
import com.cartisan.core.stereotype.SubDomain;
