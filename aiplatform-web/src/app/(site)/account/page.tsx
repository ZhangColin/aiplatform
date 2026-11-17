import type { Metadata } from "next";
import { cookies } from "next/headers";

import { MyAccountView } from "@/components/billing/my-account-view";
import { HomeHeader } from "@/components/site/home-header";
import { hasCredentialCookie } from "@/lib/auth/cookies";

export const metadata: Metadata = { title: "我的账户" };

/** 我的账户（#534 计费④）：双池余额＋流水＋项目充值入口——挂点＝账户菜单
 *  「我的账户」项（页级登录门：未登录先 M01 checkout-login 来路）。 */
export default async function AccountPage() {
  const cookieStore = await cookies();
  return (
    <div className="min-h-svh bg-canvas-base">
      <HomeHeader credentialPresent={hasCredentialCookie(cookieStore)} />
      <main className="pt-16">
        <MyAccountView />
      </main>
    </div>
  );
}
