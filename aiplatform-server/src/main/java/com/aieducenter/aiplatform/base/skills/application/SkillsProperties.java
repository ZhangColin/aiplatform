package com.aieducenter.aiplatform.base.skills.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 技能域配置（前缀 app.skills，#255）：指派预检模型档专用键——预检是后台低频
 * 轻调用，缺省落 flash 档由代码保证，不吃 {@code app.agentscope.default-model}
 * 的缺省（部署把缺省配成重档则每次预检烧一次重模型——DispatchProperties 同款
 * 取舍，#51），不依赖部署记性。
 */
@Component
@ConfigurationProperties(prefix = "app.skills")
public class SkillsProperties {

    /**
     * 预检模型串（provider:modelId，白名单见 ModelRef）：结构化重叠判定的小任务，
     * 缺省 flash 档——快且省。
     */
    private String precheckModel = "deepseek:deepseek-v4-flash";

    public String getPrecheckModel() {
        return precheckModel;
    }

    public void setPrecheckModel(String precheckModel) {
        this.precheckModel = precheckModel;
    }
}
