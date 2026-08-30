import type { Metadata } from "next";

import { EngineConfigView } from "@/components/admin-portal/engine-config-view";

export const metadata: Metadata = { title: "引擎配置" };

/**
 * 简易后台落地页 = 引擎配置（CONTEXT「简易后台」，#56）。页面结构留扩展位：
 * 后续后台内容以区块组件为单位在此追加（菜单同步在 admin-portal/portal-shell
 * 增项），整体不动壳——将来迁正式管理后台门户时整页平移。
 */
export default function AdminHomePage() {
  return <EngineConfigView />;
}
