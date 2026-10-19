# SmartDock 部署文档

## 1. 前置要求

- Docker 24+ / Docker Compose v2
- 构建机：JDK 21 + Maven 3.8+（后端），Node 20.19+（前端）
- 内存 ≥ 4G（JVM 默认 -Xmx2g 可在 compose 里调）

## 2. 构建产物

```bash
# 后端：产出 backend/ruoyi-admin/target/ruoyi-admin.jar
cd backend
mvn clean package -DskipTests

# 前端：产出 frontend/dist/
cd frontend
npm i && npm run build:prod
```

## 3. 一键起栈（deploy/）

```bash
cd deploy
docker compose up -d --build
```

编排内容：

| 服务 | 镜像 | 说明 |
|---|---|---|
| postgres | postgres:17-alpine | 首次启动自动执行 `../backend/script/sql/baseline/`（01→05 依序，含 05 演示数据；不需要时在首次启动前移出该文件）|
| redis | redis:7-alpine | 会话/幂等/限流，AOF 开启 |
| minio | quay.io/minio（钉版）| 附件对象存储；9000 API / 9001 管理台（仅本机回环）|
| backend | 自建（JDK21）| 8080；数据源/Redis 全走环境变量注入 |
| frontend | nginx:1.27-alpine | 80；等保加固头 + 登录限流 + SSE 直通代理 |

默认口令均为 `changeit`，请用环境变量覆盖：

```bash
POSTGRES_PASSWORD=... REDIS_PASSWORD=... MINIO_PASSWORD=... docker compose up -d
```

## 4. 首启后的一次性配置

1. 登录 `http://<host>/`（admin / admin123，**立即改密**）；
2. 「系统 → AI 配置」录入供应商 API Key（基线刻意不带任何密钥）；
3. 附件直连：若浏览器经宿主机访问 MinIO，把 `sys_oss_config` 中 minio 行的
   `endpoint` 改为宿主机可达地址（如 `192.168.x.x:9000`）并在 MinIO 控制台为
   后端机器创建桶策略（演示口径 minioadmin/minioadmin，生产必改）；
4. 上生产前必改三处口令：compose 的 `POSTGRES_PASSWORD/REDIS_PASSWORD/MINIO_PASSWORD`
   与 `backend/.../application.yml` 的 `jwt-secret-key`、`api-decrypt`（仓库内是可公开的演示默认值）；
5. （可选）调低 JVM：编辑 compose 的 `JAVA_OPTS`。

## 5. 裸机部署（不用 Docker）

1. PostgreSQL 17：建空库后按序执行 `backend/script/sql/baseline/` 四个 SQL；
2. Redis 7：设置 requirepass；
3. 启动：
   ```bash
   java -Duser.timezone=GMT+8 -jar ruoyi-admin.jar \
     --server.port=8090 \
     --spring.datasource.dynamic.datasource.master.url=jdbc:postgresql://127.0.0.1:5432/smartdock \
     --spring.datasource.dynamic.datasource.master.password=<你的库口令> \
     --spring.data.redis.host=127.0.0.1 --spring.data.redis.port=6379 --spring.data.redis.database=0 \
     --spring.data.redis.password=<你的Redis口令>
   ```
4. 前端 `npm run build:prod` 后把 `dist/` 交给任意静态服务器，`/prod-api` 反代到 8090
   （参考 `deploy/frontend/nginx.conf`，注意保留 SSE 关缓冲配置）。

## 6. 升级

- 后端：重新 `mvn package` 后 `docker compose up -d --build backend`；
- 数据库：本基线之后的结构变更一律新增 `NNN_*.sql` 增量脚本，按序执行；
- 回滚：镜像钉版 + 数据卷常驻，`docker compose down`（不加 `-v`）可安全回退。

## 7. 健康检查

调试/监控端点经 nginx 一律 404（等保口径，见 nginx.conf），健康状态看容器层：

```bash
docker compose ps                    # postgres/redis/minio 各自的 healthcheck 状态
docker logs -f smartdock-backend --tail 200   # 后端无独立探针, 看启动日志与 GC/异常
```
