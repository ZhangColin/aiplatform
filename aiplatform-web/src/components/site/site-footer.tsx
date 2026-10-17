import { BrandLogo } from "@/components/brand/brand-logo";

/**
 * 首页合规条 + 站点 footer（#345，spec #320 ⑥ 首页 P01 照原型）：SECURITY AUDITED
 * 合规条（数据隔离/加密说明 + 四协议链接）+ 站点 footer（品牌行 + 版权 + 三链接）。
 * 链接为占位（href="#"——上线业务参数/协议页地址走配置，老板侧待定，spec Out of
 * Scope）。
 */

/** 合规条协议链接（占位——地址上线配）。 */
const COMPLIANCE_LINKS = ["服务协议", "隐私政策", "免责说明", "交付规范标准 (SLA)"] as const;

/** 页脚资源链接（占位——地址上线配）。 */
const FOOTER_LINKS = ["安全与合规", "工程规范", "API 状态"] as const;

export function SiteFooter() {
  return (
    <>
      {/* 合规条 */}
      <section className="w-full border-t border-slate-soft/70 bg-white/70 py-8 backdrop-blur-sm">
        <div className="mx-auto flex w-full max-w-6xl flex-col items-center justify-between gap-4 px-4 text-ink-muted sm:flex-row md:px-6 lg:px-8">
          <div className="flex items-center gap-3">
            <span className="font-mono text-code-sm font-semibold tracking-wider text-ink-secondary">
              SECURITY AUDITED
            </span>
            <span className="size-1.5 rounded-full bg-emerald-active" aria-hidden="true" />
            <span className="text-body-sm">企业级私有化数据隔离 · 全链路传输加密</span>
          </div>
          <div className="flex items-center gap-5">
            {COMPLIANCE_LINKS.map((label) => (
              <a
                key={label}
                href="#"
                className="text-label-sm transition-colors hover:text-electric-indigo"
              >
                {label}
              </a>
            ))}
          </div>
        </div>
      </section>

      {/* 站点 footer */}
      <footer className="w-full border-t border-slate-soft/80 bg-white py-8">
        <div className="mx-auto flex w-full max-w-6xl flex-col items-center justify-between gap-4 px-4 md:flex-row md:px-6 lg:px-8">
          <div className="flex items-center gap-2">
            <div className="flex size-7 items-center justify-center rounded-lg border border-subtle-blue/60 bg-indigo-50">
              <BrandLogo className="size-5" />
            </div>
            <span className="text-label-md font-semibold tracking-tight text-ink-secondary">
              语义引力 SEMANTIC GRAVITY
            </span>
            <span className="font-mono text-[11px] font-medium text-ink-faint">
              · AI NATIVE DELIVERY OS
            </span>
          </div>
          <p className="text-body-sm text-ink-faint">
            © 2025 语义引力 (Semantic Gravity) AI Native Delivery Machine. All rights reserved.
          </p>
          <div className="flex items-center gap-5">
            {FOOTER_LINKS.map((label) => (
              <a
                key={label}
                href="#"
                className="text-label-sm text-ink-muted transition-colors hover:text-electric-indigo"
              >
                {label}
              </a>
            ))}
          </div>
        </div>
      </footer>
    </>
  );
}
