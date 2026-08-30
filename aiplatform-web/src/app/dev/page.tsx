import type { Metadata } from "next";

import { DevHomeView } from "@/components/dev-portal/home-view";

export const metadata: Metadata = { title: "项目列表" };

/** 开发平台落地页（spec 0003 §1）= 项目列表 + 同形态建项目入口（issue #39）。 */
export default function DevHomePage() {
  return <DevHomeView />;
}
