import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  output: "standalone",
  // SSE 穿透开关：rewrite 代理的 /api/agent-events、/api/events 在 Next 默认
  // compress（gzip 攒缓冲）下帧被扣在压缩缓冲——EventSource onopen 后一个事件
  // 都收不到（浏览器必带 Accept-Encoding，症状 = 直播/问答卡/错误提示全静默；
  // curl 不带该头则正常，故 API 级冒烟抓不到，须浏览器级验证）。SSE 与代理的
  // 动态 API 本就不该由应用层压缩，静态资源压缩交由前置设施。
  compress: false,
  // 显式保留 StrictMode 双挂载（issue #60）：换取 effect 清理问题的早期暴露；
  // dev 下由此产生的同 URL GET 双发在薄 client 用 in-flight 去重收口
  // （src/lib/api/client.ts），SSE 侧由 probe-cancel 守卫保证单连接。
  reactStrictMode: true,
  outputFileTracingIncludes: {
    "/**": ["node_modules/@swc/helpers/**"],
  },
  rewrites: async () => {
    const backendUrl = process.env.BACKEND_URL || "http://localhost:8888";
    return [
      {
        source: "/api/:path*",
        destination: `${backendUrl}/api/:path*`,
      },
      // BFF 四端点：/auth/login /auth/callback /auth/logout（spec 0004 §1，
      // 整页跳转 / form POST，非 fetch）。cookie 经代理落在 3333 origin。
      {
        source: "/auth/:path*",
        destination: `${backendUrl}/auth/:path*`,
      },
    ];
  },
};

export default nextConfig;
