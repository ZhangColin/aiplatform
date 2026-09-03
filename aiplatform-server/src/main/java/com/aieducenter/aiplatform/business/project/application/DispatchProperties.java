package com.aieducenter.aiplatform.business.project.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 派发编排配置（前缀 app.dispatch，#51）：入口三分类的分类模型档专用键——
 * 分类是每条指令区消息必经的轻调用，缺省落在 flash 档由代码保证，不吃
 * {@code app.agentscope.default-model} 的缺省（部署把缺省配成重档则每条消息
 * 烧一次重模型），不依赖部署记性。
 */
@Component
@ConfigurationProperties(prefix = "app.dispatch")
public class DispatchProperties {

    /**
     * 分类模型串（provider:modelId，白名单见 ModelRef）：单标签输出的小任务，
     * 缺省 flash 档——快且省。
     */
    private String classificationModel = "deepseek:deepseek-v4-flash";

    public String getClassificationModel() {
        return classificationModel;
    }

    public void setClassificationModel(String classificationModel) {
        this.classificationModel = classificationModel;
    }
}
