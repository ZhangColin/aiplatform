package com.aieducenter.aiplatform.business.identity.endpoints.controller;

import java.util.List;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.cartisan.web.response.ApiResponse;

import com.aieducenter.aiplatform.business.identity.application.AccountAppService;
import com.aieducenter.aiplatform.business.identity.application.dto.response.AccountResponse;

/**
 * 账号端点：全量账号清单。v1 无成员页，不分页（量小）。
 */
@RestController
@Tag(name = "账号", description = "账号清单（business.identity）")
public class AccountController {

    private final AccountAppService appService;

    public AccountController(AccountAppService appService) {
        this.appService = appService;
    }

    @GetMapping("/api/accounts")
    @Operation(summary = "账号清单",
            description = "全量账号（建档顺序、不分页）。accountId 为 TSID 十进制字符串")
    public ApiResponse<List<AccountResponse>> accounts() {
        return ApiResponse.ok(appService.list());
    }
}
