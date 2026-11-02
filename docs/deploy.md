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
npm ci && npm run build:prod
```

## 3. 一键起栈（deploy/）

```bash
cd deploy
docker compose up -d --build
```

编排内容：

| 服务 | 镜像 | 说明 |
|---|---|---|
| postgres | postgres:17-alpine | 首次启动自动执行 `../backend/script/sql/baseline/`（01→07 依序，含 05 演示数据；不需要时在首次启动前移出该文件）|
| redis | redis:7-alpine | 会话/幂等/限流，AOF 开启 |
| minio | quay.io/minio（钉版）| 附件对象存储；9000 API / 9001 管理台（仅本机回环）|
| backend | 自建（JDK21）| 8080；数据源/Redis 全走环境变量注入 |
| frontend | nginx:1.27-alpine | 80；等保加固头 + 登录限流 + SSE 直通代理 |

PostgreSQL/Redis 默认口令为 `changeit`，MinIO 为 `minioadmin`，请用环境变量覆盖：

```bash
POSTGRES_PASSWORD=... REDIS_PASSWORD=... MINIO_PASSWORD=... docker compose up -d
```

## 4. 首启后的一次性配置

1. 登录 `http://<host>/`（admin / admin123，**立即改密**）；
2. 「AI 模型配置」录入供应商 API Key（基线刻意不带任何密钥）；
3. 附件直连：若浏览器经宿主机访问 MinIO，把 `sys_oss_config` 中 minio 行的
   `endpoint` 改为宿主机可达地址（如 `192.168.x.x:9000`）并在 MinIO 控制台为
   后端机器创建桶策略（演示口径 minioadmin/minioadmin，生产必改）；
4. 上生产前必改三处口令：compose 的 `POSTGRES_PASSWORD/REDIS_PASSWORD/MINIO_PASSWORD`
   与 `backend/.../application.yml` 的 `jwt-secret-key`、`api-decrypt`（仓库内是可公开的演示默认值）；
5. （可选）调低 JVM：编辑 compose 的 `JAVA_OPTS`。

## 5. 多实例部署（AI 助手水平扩容）

单机默认 `local` 模式，零 Redis 依赖。要让两台以上后端同时服务 AI 助手，必须满足下面四条，
缺一条就会出现「确认卡在 A、作答落到 B」「停止只停了个提示」这类假成功：

1. **全集群同一个共享状态模式**：所有节点都设 `DCS_ASSISTANT_SHARED_STATE_MODE=redis`；
   不要一部分 `local`、一部分 `redis`（`local` 节点会把别台的活 run 判死并收尾）。
2. **每台实例 id 唯一**：`DCS_ASSISTANT_INSTANCE_ID=backend-a` / `backend-b`（或用默认的
   `hostname:pid:随机码`）。同一个 id 起两个进程会被启动校验拒绝——否则两边都认
   「owner 是我」，互相收掉对方的活 run。
3. **入口按会话粘性分流**：nginx 必须按 `X-Thread-Id` 做一致性哈希，并把
   `DCS_ASSISTANT_STICKY_REQUIRE_HEADER=true` 打开（缺头直接 400，避免静默落错机）：

   ```nginx
   upstream smartdock_backend {
       hash $http_x_thread_id consistent;
       server 10.0.0.11:8090;
       server 10.0.0.12:8090;
   }
   ```

   没有一致性哈希时，`/agui/run`、HITL 确认卡、反问作答、续看都可能落到没有现场的另一台；
   此时助手会回「刷新页面后再试」的人话，但体验仍然会坏。
4. **数据库先升级再放流量**：先执行 `backend/script/sql/migration/` 里本期新增的脚本
   （至少 `2026-11-01_assistant_run_owner.sql` 与 `2026-11-01_assistant_attachment_owner.sql`），
   再滚动重启后端；滚动期间先把旧节点从 upstream 摘掉，避免旧版本（没有 owner 字段）
   和新版本混跑。

> 计划卡、确认结果、实例租约放在 Redis 的 `global:smartdock:asst:` 前缀下；
> 回答正文、用户作答内容不落 Redis。本机仍保留 `threadSessionManager` 的会话现场，
> 所以「会话固定回原机器」是正确性前提，不是性能优化。

## 6. 裸机部署（不用 Docker）

1. PostgreSQL 17：建空库后按序执行 `backend/script/sql/baseline/` 的七个 SQL（01→07；05 为可选演示数据，不需要时移出该目录）；
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

## 7. 升级

- 后端：重新 `mvn package` 后 `docker compose up -d --build backend`；
- 数据库：本基线之后的结构变更一律新增 `NNN_*.sql` 增量脚本，按序执行；
- 回滚：镜像钉版 + 数据卷常驻，`docker compose down`（不加 `-v`）可安全回退。

## 8. 健康检查

调试/监控端点经 nginx 一律 404（等保口径，见 nginx.conf），健康状态看容器层：

```bash
docker compose ps                    # postgres/redis/minio 各自的 healthcheck 状态
docker logs -f smartdock-backend --tail 200   # 后端无独立探针, 看启动日志与 GC/异常
```
