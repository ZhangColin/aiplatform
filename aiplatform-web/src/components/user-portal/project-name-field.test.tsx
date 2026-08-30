// @vitest-environment happy-dom

import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, afterEach, describe, expect, it, vi } from "vitest";
import { toast } from "sonner";

import { ApiError } from "@/lib/api/api-error";
import { api } from "@/lib/api/client";
import { queryKeys } from "@/lib/api/keys";
import type { ProjectDetail } from "@/lib/main-chain/project";

import { ProjectNameField } from "./project-name-field";

// 项目名 inline 改名（issue #55，spec 0002 §4）：右栏「项目信息」区的编辑态交互。
// api client 与 sonner 均桩掉；「服务端」为可变态——get 恒回当前详情，post 改名
// 成功后就地更新，使 invalidate 触发的后台重拉与新播种一致（模拟真实后端）。
const server = vi.hoisted(() => ({
  raw: {
    id: "p1",
    name: "宠物医院预约官网",
    stage: "REQUIREMENT",
    stageLabel: "需求梳理",
    stages: [],
    gate: null,
  } as Record<string, unknown>,
}));

vi.mock("@/lib/api/client", () => ({
  api: {
    get: vi.fn(async () => server.raw),
    post: vi.fn(),
  },
}));

vi.mock("sonner", () => ({
  toast: { error: vi.fn(), warning: vi.fn() },
}));

/** 播种口径的详情（useProject 缓存里存归一后形状，doc-panel.test 同款）。 */
const DETAIL: ProjectDetail = {
  id: "p1",
  name: "宠物医院预约官网",
  stage: "REQUIREMENT",
  stageLabel: "需求梳理",
  stages: [],
  gate: null,
};

function renderField() {
  const qc = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  qc.setQueryData(queryKeys.projects.detail("p1"), DETAIL);
  return render(
    <QueryClientProvider client={qc}>
      <ProjectNameField projectId="p1" />
    </QueryClientProvider>,
  );
}

function enterEdit() {
  fireEvent.click(screen.getByRole("button", { name: "编辑项目名" }));
  return screen.getByLabelText("项目名称") as HTMLInputElement;
}

beforeEach(() => {
  server.raw = { ...server.raw, name: DETAIL.name };
  vi.clearAllMocks();
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe("ProjectNameField · 展示态与编辑入口", () => {
  it("渲染当前项目名 + 编辑入口", () => {
    renderField();
    expect(screen.getByText("宠物医院预约官网")).not.toBeNull();
    expect(screen.getByRole("button", { name: "编辑项目名" })).not.toBeNull();
  });

  it("进入编辑：输入框初值 = 当前名并聚焦；Esc 取消回到展示态", () => {
    renderField();
    const input = enterEdit();
    expect(input.value).toBe("宠物医院预约官网");
    expect(document.activeElement).toBe(input);

    fireEvent.keyDown(input, { key: "Escape" });
    expect(screen.queryByLabelText("项目名称")).toBeNull();
    expect(screen.getByText("宠物医院预约官网")).not.toBeNull();
  });
});

describe("ProjectNameField · 校验分支（口径与创建一致，就地提示不发请求）", () => {
  it("空名提交：报「项目名不能为空」，停留编辑态", () => {
    renderField();
    const input = enterEdit();
    fireEvent.change(input, { target: { value: "   " } });
    fireEvent.click(screen.getByRole("button", { name: "保存" }));

    expect(screen.getByRole("alert").textContent).toBe("项目名不能为空");
    expect(screen.getByLabelText("项目名称")).not.toBeNull();
    expect(api.post).not.toHaveBeenCalled();
  });

  it("超长提交：报长度上限", () => {
    renderField();
    const input = enterEdit();
    fireEvent.change(input, { target: { value: "a".repeat(101) } });
    fireEvent.click(screen.getByRole("button", { name: "保存" }));

    expect(screen.getByRole("alert").textContent).toBe("项目名不能超过 100 字");
    expect(api.post).not.toHaveBeenCalled();
  });
});

describe("ProjectNameField · 提交分支", () => {
  it("成功：POST …/rename（trim 后载荷）→ 退出编辑态、显示新名", async () => {
    vi.mocked(api.post).mockImplementationOnce(async (_path, body) => {
      const name = (body as { name: string }).name;
      server.raw = { ...server.raw, name };
      return server.raw;
    });
    renderField();
    const input = enterEdit();
    fireEvent.change(input, { target: { value: "  在线预约平台  " } });
    fireEvent.click(screen.getByRole("button", { name: "保存" }));

    // mutationFn 异步调度，载荷断言等它落地
    await waitFor(() =>
      expect(api.post).toHaveBeenCalledWith("/projects/p1/rename", { name: "在线预约平台" }),
    );
    // 新名来自改名响应播种的详情缓存（列表 / 顶栏同一失效口径）
    expect(await screen.findByText("在线预约平台")).not.toBeNull();
    expect(screen.queryByLabelText("项目名称")).toBeNull();
  });

  it("失败：toast 直出后端 message，停留编辑态可重试", async () => {
    vi.mocked(api.post).mockRejectedValueOnce(
      new ApiError({ status: 409, code: "PRJ_016", message: "项目名已被使用" }),
    );
    renderField();
    const input = enterEdit();
    fireEvent.change(input, { target: { value: "撞名" } });
    fireEvent.click(screen.getByRole("button", { name: "保存" }));

    await waitFor(() => expect(toast.error).toHaveBeenCalledWith("项目名已被使用"));
    // 停留编辑态：名字仍在输入框里，未被错误清掉
    const inputAfter = screen.getByLabelText("项目名称") as HTMLInputElement;
    expect(inputAfter.value).toBe("撞名");
  });
});
