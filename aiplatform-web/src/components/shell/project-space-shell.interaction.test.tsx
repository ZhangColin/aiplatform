// @vitest-environment happy-dom
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import type { ProjectDetail } from "@/lib/projects/detail";
import { useM01Store } from "@/lib/store/m01";
import { useSaveStateStore } from "@/lib/store/save-state";

import { ProjectSpaceShell } from "./project-space-shell";

// 壳装配的交互面（#343）：灰置点击说明原因、保存胶囊三态（store 真实转移）、
// 「保存项目」→M01 触发挂点、五行导航与侧栏的阶段重复面。项目详情桩＝门全关
//（今日真实投影：PRD/Demo 确认机制未落地）；登录态桩＝已登录。

const seed = vi.hoisted(() => ({
  detail: {} as ProjectDetail,
  prdVersions: [] as { versionNo?: number }[],
}));

vi.mock("next/navigation", () => ({ usePathname: () => "/projects/p1" }));
vi.mock("@/hooks/use-project", () => ({
  useProject: () => ({ data: seed.detail, isPending: false }),
}));
vi.mock("@/hooks/use-prd", () => ({
  usePrdVersions: () => ({ data: seed.prdVersions }),
}));
// Demo 版本列表（#333）：门事实投影输入——默认空（无确认标记，Demo 确认门关）
vi.mock("@/hooks/use-demo-version", () => ({
  useDemoVersions: () => ({ data: [] }),
}));
vi.mock("@/hooks/use-me", () => ({
  useMe: () => ({ data: { displayName: "测试用户" }, isPending: false }),
}));

beforeEach(() => {
  seed.detail = {} as ProjectDetail;
  seed.prdVersions = [];
});

afterEach(() => {
  cleanup();
  useSaveStateStore.setState({ state: "saved" });
  useM01Store.setState({ open: false, trigger: null });
});

function mountShell() {
  return render(
    <ProjectSpaceShell projectId="p1">
      <div>页面内容</div>
    </ProjectSpaceShell>,
  );
}

describe("ProjectSpaceShell · 灰置点击说明原因", () => {
  it("侧栏：点灰置的「Demo 体验」出原因（PRD 确认门）", async () => {
    mountShell();
    // 侧栏与顶栏同名重复面：取全部命中里的第一枚
    fireEvent.click(screen.getAllByText("Demo 体验")[0]);
    expect(await screen.findByText(/PRD 确认后开放/)).toBeTruthy();
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

  it("#343 验收闭合：PRD 已确认（#330 版本列表非空）→ Demo 体验不再灰置、指到阶段页", () => {
    seed.prdVersions = [{ versionNo: 1 }];
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

    fireEvent.click(screen.getByRole("button", { name: "保存项目" })); // 顺带：触发 M01
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

describe("ProjectSpaceShell · 「保存项目」触发 M01（挂点）", () => {
  it("点击即开 M01、trigger 记来路 save-project", () => {
    mountShell();
    expect(useM01Store.getState().open).toBe(false);
    fireEvent.click(screen.getByRole("button", { name: "保存项目" }));
    expect(useM01Store.getState().open).toBe(true);
    expect(useM01Store.getState().trigger).toBe("save-project");
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
