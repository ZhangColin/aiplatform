# 后端镜像·部署变体（原 aiplatform-server/Dockerfile 不动）：唯一差异＝加 docker-cli——
# 沙箱机制是后端经 docker CLI 子进程驱动工作区容器（DockerEnvironmentBackend），
# 容器内须有 docker 客户端，sock 由 deploy/server.compose.yml 挂载。
# 构建上下文＝aiplatform-server/（jar 须先落 target/）。
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN apk add --no-cache tzdata curl docker-cli && \
    cp /usr/share/zoneinfo/Asia/Shanghai /etc/localtime && \
    echo "Asia/Shanghai" > /etc/timezone && \
    apk del tzdata
COPY target/aiplatform-server-1.0.0-SNAPSHOT.jar app.jar
EXPOSE 8888
ENTRYPOINT ["java", "--enable-preview", "-Duser.timezone=Asia/Shanghai", "-jar", "app.jar"]
