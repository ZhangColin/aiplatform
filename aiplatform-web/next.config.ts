import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  output: "standalone",
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
