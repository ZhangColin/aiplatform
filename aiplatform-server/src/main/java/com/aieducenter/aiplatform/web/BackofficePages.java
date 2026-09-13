package com.aieducenter.aiplatform.web;

import java.util.List;

import com.cartisan.web.response.PageResponse;

/**
 * 后台清单面分页钳（#151「分页与排序」口径的单点：page 1 基、size 上界 100、
 * 排序服务端定死）。四处兄弟镜像收编（订单/项目/单价表/项目成本——#159 备案
 * 「第三张后台清单面出现时抽共享」，#164 第四张落地时兑现）；非法分页值在
 * controller 绑定层即 400（各域过滤参数错误码），本钳只兜越界数值。
 */
public final class BackofficePages {

    /** 页大小上界（防一次性拉穿；监管清单一屏用不到更大）。 */
    public static final int MAX_PAGE_SIZE = 100;

    private BackofficePages() {
    }

    /** page 1 基下钳（0/负数归 1）。 */
    public static int clampPage(int page) {
        return Math.max(page, 1);
    }

    /** size 钳入 {@code [1, 100]}。 */
    public static int clampSize(int size) {
        return Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    }

    /** 如实空页（检索维度无命中的 200 空清单，非错误）。 */
    public static <T> PageResponse<T> emptyPage(int page, int size) {
        return new PageResponse<>(List.of(), 0, page, size);
    }
}
