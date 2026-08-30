package com.aieducenter.aiplatform.base.agentengine.application;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.cartisan.core.exception.ApplicationException;

import com.aieducenter.aiplatform.base.agentengine.domain.error.AgentEngineMessage;
import com.aieducenter.aiplatform.base.agentengine.domain.port.WaitResponder;

/**
 * 等待点答复派发目录：按 engine 名索引 {@link WaitResponder}——答复通道以裸
 * bean 并入（当前唯一通道 = 对话智能体 agentscope；多通道重名 fail-fast）。
 */
@Component
public class WaitResponderDirectory {

    private final Map<String, WaitResponder> responders = new LinkedHashMap<>();

    public WaitResponderDirectory(List<WaitResponder> responders) {
        for (WaitResponder responder : responders) {
            WaitResponder existing = this.responders.put(responder.engine(), responder);
            if (existing != null && existing != responder) {
                throw new IllegalStateException("等待点答复通道重名双实现: "
                        + responder.engine());
            }
        }
    }

    /** 按名取答复通道；未登记抛 AGT_001（404）。 */
    public WaitResponder require(String engine) {
        WaitResponder responder = responders.get(engine);
        if (responder == null) {
            throw new ApplicationException(AgentEngineMessage.ENGINE_NOT_FOUND);
        }
        return responder;
    }
}
