package com.aieducenter.aiplatform.base.agentengine.application.dto.response;

/**
 * settle 用例结果：关闭后的等待点投影 + 会话引擎 + 是否达 deny cap（同 run 内
 * 权限拒绝累计达上限的判定回报——接续动作归调用方）。
 */
public record SettleResult(WaitPointResponse settled, String engine, boolean denyCapped) {
}
