# SmartDock 后端镜像（批7）：JDK21 + 强制 GMT+8（时区自检铁律）
FROM bellsoft/liberica-openjdk-rocky:21.0.8-cds

RUN mkdir -p /app/logs /app/temp
WORKDIR /app

ENV SERVER_PORT=8080 LANG=C.UTF-8 LC_ALL=C.UTF-8 \
    TZ=Asia/Shanghai JAVA_OPTS=""

EXPOSE ${SERVER_PORT}

ADD backend/ruoyi-admin/target/ruoyi-admin.jar ./app.jar

SHELL ["/bin/bash", "-c"]

# exec 让 java 顶替 shell 成 PID1, docker stop 的 SIGTERM 才能直达(全审 P1)
ENTRYPOINT ["/bin/bash", "-c", "exec java -Djava.security.egd=file:/dev/./urandom -Dserver.port=${SERVER_PORT} -Duser.timezone=GMT+8 -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/app/logs -XX:+ExitOnOutOfMemoryError -XX:MaxMetaspaceSize=512m -XX:+UseZGC -XX:+ZGenerational ${JAVA_OPTS} -jar app.jar"]
