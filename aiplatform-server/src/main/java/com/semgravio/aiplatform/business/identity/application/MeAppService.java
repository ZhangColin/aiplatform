package com.semgravio.aiplatform.business.identity.application;

import org.springframework.stereotype.Service;

import com.cartisan.core.context.RequestContext;

import com.semgravio.aiplatform.business.identity.application.dto.response.MeResponse;
import com.semgravio.aiplatform.web.AuthException;

/**
 * 当前账号查询（A2 §3：/api/me 纯读）。claims 已在 callback 建档时映射进会话，
 * 此处不查库。#326 起 /api/** 拦截面放行「无账户会话但有有效访客凭证」的请求——
 * 本端点是<b>账户</b>端点：访客无账户档案，按无账户会话口径 401（前端据此呈现
 * 未登录态，访客身份感知走项目面而非 /me）。
 */
@Service
public class MeAppService {

    public MeResponse currentAccount() {
        if (RequestContext.getUserId() == null) {
            throw AuthException.unauthorized(); // 访客凭证过得了拦截面、过不了账户端点
        }
        return new MeResponse(
                String.valueOf(RequestContext.getUserId()),
                RequestContext.getUserName());
    }
}
