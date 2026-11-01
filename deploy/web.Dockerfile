# 前端镜像·部署变体（原 aiplatform-web/Dockerfile 不动），两处差异——
# ① BACKEND_URL 以 build arg 构建期烤入：next.config.ts 的 rewrites 是构建期求值进
#   routes-manifest，运行时环境变量无效（compose env_file 传只是占位）；
# ② pnpm install 走 npmmirror（大陆构建机）。
FROM node:20-alpine AS deps
WORKDIR /app
COPY package.json pnpm-lock.yaml* ./
RUN corepack enable && pnpm install --frozen-lockfile --registry=https://registry.npmmirror.com || pnpm install --registry=https://registry.npmmirror.com

FROM node:20-alpine AS builder
WORKDIR /app
COPY --from=deps /app/node_modules ./node_modules
COPY . .
ARG BACKEND_URL=http://localhost:8888
ENV BACKEND_URL=${BACKEND_URL}
ENV NEXT_TELEMETRY_DISABLED=1
RUN corepack enable && pnpm build

FROM node:20-alpine AS runner
WORKDIR /app
ENV NODE_ENV=production
ENV NEXT_TELEMETRY_DISABLED=1
RUN addgroup --system --gid 1001 nodejs && \
    adduser --system --uid 1001 nextjs
COPY --from=builder /app/public ./public
COPY --from=builder --chown=nextjs:nodejs /app/.next/standalone ./
COPY --from=builder --chown=nextjs:nodejs /app/.next/static ./.next/static
USER nextjs
EXPOSE 3333
ENV PORT=3333
CMD ["node", "server.js"]
