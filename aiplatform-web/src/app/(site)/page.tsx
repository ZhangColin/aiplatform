import { HomeHeader } from "@/components/site/home-header";
import { HomeHero } from "@/components/site/home-hero";
import { HomeMetrics } from "@/components/site/home-metrics";
import { SiteFooter } from "@/components/site/site-footer";
import { WorkflowPipeline } from "@/components/site/workflow-pipeline";

/**
 * 首页 P01（#345，spec #320 ⑥ IA 全量重建，照原型全量）：顶栏（首页无侧栏）＋
 * Hero（徽章/主标/副标/草稿恢复横幅/大输入卡/示例 chip×3）＋流程画板 PHASE
 * 01/02（两段九步＋锁版里程碑＋保障条）＋指标卡×4＋合规条/footer。话术逐字照
 * 原型（含装饰/夸张文案，清理留老板上线前后过）。
 */
export default function HomePage() {
  return (
    <div className="min-h-svh bg-canvas-base">
      <HomeHeader />
      <main className="pt-16">
        <HomeHero />
        <WorkflowPipeline />
        <HomeMetrics />
      </main>
      <SiteFooter />
    </div>
  );
}
