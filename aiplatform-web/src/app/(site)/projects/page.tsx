import type { Metadata } from "next";
import { cookies } from "next/headers";

import { HomeHeader } from "@/components/site/home-header";
import { ProjectListView } from "@/components/site/project-list-view";
import { GUEST_COOKIE, hasCredentialCookie, SESSION_COOKIE } from "@/lib/auth/cookies";

export const metadata: Metadata = { title: "我的项目" };

/** 我的项目 P08（#349）：全局顶栏＋账号全量列表；仅访客凭证时只回当前会话项目。 */
export default async function ProjectsPage() {
  const cookieStore = await cookies();
  const guestOnly = !cookieStore.has(SESSION_COOKIE) && cookieStore.has(GUEST_COOKIE);
  return (
    <div className="min-h-svh bg-canvas-base">
      <HomeHeader credentialPresent={hasCredentialCookie(cookieStore)} />
      <main className="pt-16">
        <ProjectListView guestOnly={guestOnly} />
      </main>
    </div>
  );
}
