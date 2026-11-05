import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  // #395：预览域 Origin 放行（不带协议/端口，* 恰匹配一个 label）——不经网关
  // 直连应用口（容器内排查等）时，带预览域 Origin 的 /_next 请求不被 dev 闸门
  // 403，行为与经网关一致（ADR-0034：语义自文档，修复主力在网关收口）。
  allowedDevOrigins: ["*.preview.semgravio.com"],
};

export default nextConfig;
