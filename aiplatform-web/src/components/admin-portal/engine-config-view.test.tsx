// @vitest-environment happy-dom

import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { toast } from "sonner";

import { ApiError } from "@/lib/api/api-error";
import { api } from "@/lib/api/client";

import { EngineConfigView } from "./engine-config-view";

// 简易后台引擎配置页（issue #56，CONTEXT「简易后台」）：当前生效引擎
// （GET /admin/engine-config）+ 引擎能力矩阵只读渲染（GET /agent-engines）+
// 切换动作（PUT /admin/engine-config，确认文案明示生效口径 = 新项目生效、
// 存量保持）。api client 与 sonner 均桩掉；「服务端」为可变态——put 改 engine
// 后 get 返回新值，invalidate 触发的重拉与播种一致（project-name-field 同款）。
const server = vi.hoisted(() => ({
  engine: "opencode",
  engines: [
    {
      name: "opencode",
      label: "OpenCode",
      questionSupported: true,
      permissionSupported: true,
      note: "注册表缺省引擎",
    },
    {
      name: "claude-code",
      label: "Claude Code",
      questionSupported: true,
      permissionSupported: false,
      note: null,
    },
    {
      name: "deepseek",
      label: "DeepSeek Harness",
      questionSupported: false,
      permissionSupported: false,
      note: "无问答/权限能力",
    },
  ],
}));

vi.mock("@/lib/api/client", () => ({
  api: {
    get: vi.fn(async (path: string) =>
      path === "/admin/engine-config" ? { engine: server.engine } : server.engines,
    ),
    put: vi.fn(async (_path: string, body: { engine: string }) => {
      if (!server.engines.some((e) => e.name === body.engine)) {
        throw new ApiError({ status: 400, code: "AGT_009", message: "引擎不在注册表内" });
      }
      server.engine = body.engine;
      return { engine: body.engine };
    }),
  },
}));

vi.mock("sonner", () => ({
  toast: { success: vi.fn(), error: vi.fn() },
}));

function renderView() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <EngineConfigView />
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  server.engine = "opencode";
  vi.clearAllMocks();
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe("EngineConfigView · 展示（当前生效引擎 + 能力矩阵只读渲染）", () => {
  it("当前生效引擎位显示矩阵对应 label，当前行标「当前生效」", async () => {
    renderView();
    // 配置端点返回 name=opencode → 展示位 join 矩阵得 label（矩阵行同 label 并存）
    expect(await screen.findAllByText("OpenCode")).not.toBeNull();
    expect(screen.getByText("当前生效")).not.toBeNull();
    // 矩阵三行全渲染（label + name 副文本）
    expect(screen.getByText("Claude Code")).not.toBeNull();
    expect(screen.getByText("DeepSeek Harness")).not.toBeNull();
    expect(screen.getByText("opencode")).not.toBeNull();
  });

  it("能力列按矩阵渲染（支持/—），备注列透出 note", async () => {
    renderView();
    await screen.findAllByText("OpenCode");
    // deepseek：问答/权限均不支持（—）
    expect(screen.getByText("DeepSeek Harness").closest("tr")!.textContent).toContain("—");
    expect(screen.getByText("注册表缺省引擎")).not.toBeNull();
  });

  it("当前项不出现「设为当前」，非当前项每行一个", async () => {
    renderView();
    await screen.findAllByText("OpenCode");
    const buttons = screen.getAllByRole("button", { name: "设为当前" });
    expect(buttons).toHaveLength(2); // claude-code + deepseek
  });
});

describe("EngineConfigView · 切换动作（PUT /admin/engine-config）", () => {
  it("确认弹窗明示生效口径（新项目生效、存量保持），取消不发请求", async () => {
    renderView();
    await screen.findAllByText("OpenCode");
    fireEvent.click(screen.getAllByRole("button", { name: "设为当前" })[0]);

    // 弹窗文案含目标引擎 + 生效口径两要素（口径词页头亦有，收窄弹窗容器内断言）
    const dialog = document.querySelector<HTMLElement>('[data-slot="alert-dialog-content"]');
    expect(dialog).not.toBeNull();
    expect(within(dialog!).getByText(/切换生效引擎/)).not.toBeNull();
    expect(within(dialog!).getByText(/新项目/)).not.toBeNull();
    expect(within(dialog!).getByText(/存量项目/)).not.toBeNull();

    fireEvent.click(screen.getByRole("button", { name: "再想想" }));
    expect(api.put).not.toHaveBeenCalled();
    // 弹窗关闭后回到矩阵
    expect(screen.queryByText(/切换生效引擎/)).toBeNull();
  });

  it("确认：PUT 载荷 = 目标 name → 成功后当前项变更", async () => {
    renderView();
    await screen.findAllByText("OpenCode");
    fireEvent.click(screen.getAllByRole("button", { name: "设为当前" })[0]);
    fireEvent.click(screen.getByRole("button", { name: "确认切换" }));

    await waitFor(() =>
      expect(api.put).toHaveBeenCalledWith("/admin/engine-config", { engine: "claude-code" }),
    );
    await waitFor(() => expect(toast.success).toHaveBeenCalled());
    // invalidate 重拉播种（server.engine 已被 put 更新）→ 当前位变更为新引擎
    expect(await screen.findAllByText("Claude Code")).not.toBeNull();
    // 新当前项不再有切换钮，剩余 = opencode + deepseek 两行
    expect(screen.getAllByRole("button", { name: "设为当前" })).toHaveLength(2);
  });

  it("非法值被拒（400 AGT_009）：toast 直出后端 message，当前值不变", async () => {
    // 绕过按钮来源模拟非法值（如注册表刚换、矩阵缓存过期）
    vi.mocked(api.put).mockRejectedValueOnce(
      new ApiError({ status: 400, code: "AGT_009", message: "引擎不在注册表内" }),
    );
    renderView();
    await screen.findAllByText("OpenCode");
    fireEvent.click(screen.getAllByRole("button", { name: "设为当前" })[0]);
    fireEvent.click(screen.getByRole("button", { name: "确认切换" }));

    await waitFor(() => expect(toast.error).toHaveBeenCalledWith("引擎不在注册表内"));
    // server.engine 未被改动 → 当前位仍是 opencode
    expect(await screen.findAllByText("OpenCode")).not.toBeNull();
  });
});
