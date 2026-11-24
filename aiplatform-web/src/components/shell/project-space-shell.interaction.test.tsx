// @vitest-environment happy-dom
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import type { ProjectDetail } from "@/lib/projects/detail";
import { useM01Store } from "@/lib/store/m01";
import { useSaveStateStore } from "@/lib/store/save-state";
import { useVersionRecordStore } from "@/lib/store/version-record";

import { ProjectSpaceShell } from "./project-space-shell";

// 壳装配的交互面（#343）：灰置点击说明原因、保存胶囊三态（store 真实转移）、
// 五行导航与侧栏的阶段重复面；#344 增「版本记录」抽屉挂载＋顶栏挂点。项目
// 详情桩＝门全关（今日真实投影：PRD/Demo 确认机制未落地）；登录态桩＝已登录
// （顶栏访客「登录」入口交互归 global-topbar.interaction；原「保存项目」入口
// 已并入「登录」）。

const seed = vi.hoisted(() => ({
  detail: {} as ProjectDetail,
}));

vi.mock("next/navigation", () => ({ usePathname: () => "/projects/p1" }));
vi.mock("@/hooks/use-project", () => ({
  useProject: () => ({ data: seed.detail, isPending: false }),
}));
// 单轴版本时间线数据/动作面（#626）——壳测试只断言挂载与开合，桩空
vi.mock("@/hooks/use-version", () => ({
  useGitVersions: () => ({ data: [], isPending: false, isError: false }),
  useRollbackVersion: () => ({ mutate: vi.fn(), isPending: false }),
}));
vi.mock("@/hooks/use-project-preview", () => ({
  useProjectPreview: () => ({ data: undefined, refetch: vi.fn() }),
}));
vi.mock("@/hooks/use-me", () => ({
  useMe: () => ({ data: { displayName: "测试用户" }, isPending: false }),
}));

beforeEach(() => {
  seed.detail = {} as ProjectDetail;
});

afterEach(() => {
  cleanup();
  useSaveStateStore.setState({ state: "saved" });
  useM01Store.setState({ open: false, trigger: null });
  useVersionRecordStore.setState({ open: false });
});

function mountShell() {
  return render(
    <ProjectSpaceShell projectId="p1">
      <div>页面内容</div>
    </ProjectSpaceShell>,
  );
}

describe("ProjectSpaceShell · 灰置点击说明原因", () => {
  it("侧栏：点灰置的「Demo 体验」出原因（首个 Demo 未起）", async () => {
    mountShell();
    // 侧栏与顶栏同名重复面：取全部命中里的第一枚
    fireEvent.click(screen.getAllByText("Demo 体验")[0]);
    expect(await screen.findByText(/首个 Demo 生成后开放/)).toBeTruthy();
  });

  it("顶栏：点灰置的「签约付款」出原因（Demo 确认门）", async () => {
    mountShell();
    fireEvent.click(screen.getAllByText("签约付款")[1]);
    expect(await screen.findByText(/Demo 确认后开放/)).toBeTruthy();
  });

  it("生产承接灰置原因＝付款门", async () => {
    mountShell();
    fireEvent.click(screen.getAllByText("生产承接")[0]);
    expect(await screen.findByText(/付款完成后开放/)).toBeTruthy();
  });

  it("#343 验收闭合：首个构建已起跑（生成态，旧版本记行随 #630 退场）→ Demo 体验不再灰置、指到阶段页", () => {
    seed.detail = { generationState: "generated" } as ProjectDetail;
    mountShell();
    const demo = screen.getAllByText("Demo 体验")[0];
    expect(demo.closest("a")?.getAttribute("href")).toBe("/projects/p1/demo");
    expect(screen.queryByText(/PRD 确认后开放/)).toBeNull();
  });
});

describe("ProjectSpaceShell · 保存状态胶囊三态（store 真实转移）", () => {
  it("缺省已保存（原型话术）→ 保存中… → 保存失败，请重试", async () => {
    mountShell();
    expect(screen.getByText("浏览器已自动保存 · 绑定可跨设备")).toBeTruthy();

    // 保存中（#326 接线方在保存动作处转移——此处直接以写面驱动，胶囊读态即换）
    useSaveStateStore.getState().markSaving();
    expect(await screen.findByText("保存中…")).toBeTruthy();

    useSaveStateStore.getState().markFailed();
    expect(await screen.findByText("保存失败，请重试")).toBeTruthy();
    // 回落已保存
    useSaveStateStore.getState().markSaved();
    expect(await screen.findByText("浏览器已自动保存 · 绑定可跨设备")).toBeTruthy();
  });
});

describe("ProjectSpaceShell · 五行导航与侧栏重复面（原型如此）", () => {
  it("四阶段名在侧栏与顶栏各呈现一份（getAllByText ≥ 2）；页面内容在壳内", () => {
    mountShell();
    for (const label of ["需求分析 (PRD)", "Demo 体验", "签约付款", "生产承接"]) {
      expect(screen.getAllByText(label).length).toBeGreaterThanOrEqual(2);
    }
    expect(screen.getByText("页面内容")).toBeTruthy();
    expect(screen.getByText("首页")).toBeTruthy();
    expect(screen.getByText("我的项目")).toBeTruthy();
  });
});

describe("ProjectSpaceShell · 版本时间线挂载与顶栏挂点（#626 单轴）", () => {
  it("顶栏出「版本记录」；点击开时间线（store 契约＝版本 chip 同挂点共用）", () => {
    mountShell();
    fireEvent.click(screen.getByRole("button", { name: "版本记录" }));
    expect(useVersionRecordStore.getState().open).toBe(true);
  });

  it("壳挂载时间线本体：store 开后出「版本」标题与空态；旧双轴 PRD/Demo 组不再呈现", () => {
    mountShell();
    fireEvent.click(screen.getByRole("button", { name: "版本记录" }));
    // 抽屉标题（heading 位）——顶栏按钮同名不误伤
    expect(screen.getByRole("heading", { name: "版本" })).toBeTruthy();
    expect(screen.getByText(/尚无版本/)).toBeTruthy();
    expect(screen.queryByRole("region", { name: "PRD 版本" })).toBeNull();
    expect(screen.queryByRole("region", { name: "Demo 版本" })).toBeNull();
  });
});

describe("ProjectSpaceShell · 移动导航抽屉（#350 破点①消解：窄屏侧栏抽屉补打开入口）", () => {
  // 断言走文本计数不走 role：抽屉是 modal（背景对 AT aria-hidden），role 查询
  // 只回抽屉内节点是 base-ui 实现细节；「第三份进场/收回」以文本拷贝数锁定
  it("顶栏汉堡键（窄屏限定 lg:hidden）开抽屉：阶段导航第三份进场", async () => {
    mountShell();
    const trigger = screen.getByRole("button", { name: "打开导航" });
    expect(trigger.className).toContain("lg:hidden");

    fireEvent.click(trigger);

    // 抽屉开出第三份阶段导航（桌面侧栏＋顶栏两份既在）
    expect((await screen.findAllByText("Demo 体验")).length).toBeGreaterThanOrEqual(3);
    expect(screen.getAllByText("需求分析 (PRD)").length).toBeGreaterThanOrEqual(3);
  });

  it("抽屉内点导航链接即关抽屉（收回第三份）", async () => {
    mountShell();
    fireEvent.click(screen.getByRole("button", { name: "打开导航" }));
    expect((await screen.findAllByText("Demo 体验")).length).toBeGreaterThanOrEqual(3);

    const drawerLink = screen.getAllByText("需求分析 (PRD)").at(-1)!.closest("a");
    expect(drawerLink).toBeTruthy();
    fireEvent.click(drawerLink!);

    await waitFor(() => {
      expect(screen.getAllByText("需求分析 (PRD)").length).toBe(2);
    });
  });
});
