package com.aieducenter.aiplatform.web;

import java.util.concurrent.Callable;

/**
 * 请求上下文的访客维度（#326 访客域）：{@code RequestContext}（cartisan）无访客
 * 腿且不归本仓改——平台侧自持的 ScopedValue 槽位，与账户维度（userId）并立。
 *
 * <p>住在顶层 web 包（跨切面）：绑定方是 identity BC 的会话过滤器，读方横跨
 * business（项目 owner 谓词/建项目归属/拦截器）与 base（SSE 订阅握手）——放任一
 * 分区都会造成分区依赖违例，跨切面同 {@link AuthException} 先例。</p>
 *
 * <p>语义：非空＝请求携带<b>有效</b>访客凭证（无状态校验已过——行存在且未作废
 * 且在保留期内）；空/未绑＝无访客维度（匿名或账户会话在场，账户会话优先于访客
 * 凭证的取舍归 {@code BffSessionContextFilter} 装配序）。ASYNC 再入（SSE 完成
 * 回调）不绑定——各拦截器对 ASYNC 放行的既有口径同源。</p>
 */
public final class GuestContext {

    private static final ScopedValue<Long> GUEST_ID = ScopedValue.newInstance();

    private GuestContext() {
    }

    /** 当前请求的访客 id（未绑/无访客维度＝null）。 */
    public static Long guestId() {
        return GUEST_ID.isBound() ? GUEST_ID.get() : null;
    }

    /** 在访客维度下执行（过滤器装配与测试夹具的注入入口；null 视同不绑）。 */
    public static <T> T runWith(Long guestId, Callable<T> action) throws Exception {
        if (guestId == null) {
            return action.call();
        }
        return ScopedValue.where(GUEST_ID, guestId).call(action::call);
    }
}
