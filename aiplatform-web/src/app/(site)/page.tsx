import { cookies } from "next/headers";

import { HomeHeader } from "@/components/site/home-header";
import { HomeHero } from "@/components/site/home-hero";
import { HomeMetrics } from "@/components/site/home-metrics";
import { SiteFooter } from "@/components/site/site-footer";
import { WorkflowPipeline } from "@/components/site/workflow-pipeline";
import { hasCredentialCookie } from "@/lib/auth/cookies";

/**
 * 首页 P01（#345，spec #320 ⑥ IA 全量重建，照原型全量）：顶栏（首页无侧栏）＋
 * Hero（徽章/主标/副标/草稿恢复横幅/大输入卡/示例 chip×3）＋流程画板 PHASE
 * 01/02（两段九步＋锁版里程碑＋保障条）＋指标卡×4＋合规条/footer。话术逐字照
 * 原型（含装饰/夸张文案，清理留老板上线前后过）。
 *
 * <p>凭证存在性在服务端 cookie 面判定（HttpOnly，脚本不可读）后下传：完全匿名
 * 首访「只逛零足迹」——最近项目/会话身份背景查询不发请求（A1 回归修复；
 * 草稿恢复横幅只在有最近项目时呈现）。</p>
 */
export default async function HomePage() {
  const cookieStore = await cookies();
  const credentialPresent = hasCredentialCookie(cookieStore);
  return (
    <div className="min-h-svh bg-canvas-base">
      <HomeHeader credentialPresent={credentialPresent} />
      <main className="pt-16">
        <HomeHero credentialPresent={credentialPresent} />
        <WorkflowPipeline />
        <HomeMetrics />
      </main>
      <SiteFooter />
    </div>
  );
}
