import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "系统",
  description: "系统",
};

export default function RootLayout({
  children,
}: Readonly<{ children: React.ReactNode }>) {
  return (
    <html lang="zh-CN">
      <body className="min-h-screen bg-white text-gray-900 antialiased">
        {children}
      </body>
    </html>
  );
}
