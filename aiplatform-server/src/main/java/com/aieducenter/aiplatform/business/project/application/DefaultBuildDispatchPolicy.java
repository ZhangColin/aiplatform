package com.aieducenter.aiplatform.business.project.application;

import org.springframework.stereotype.Component;

/**
 * 构建派发规则缝缺省实现（#332）：v1 缺省免费不限次——两挂点皆空实现（说明位
 * 返回 null、执行前确认位不激活），行为无感。老板侧参数定了以后换实现策略即生效，
 * 结构零改动。
 */
@Component
public class DefaultBuildDispatchPolicy implements BuildDispatchPolicy {
}
