package org.dromara.assistant.config;

import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 助手压缩工作区装配（批17 Q2，2026-10-16）。
 *
 * <p>WorkspaceManager 供压缩引擎做长消息卸载归档（offload），进程级单例即可。
 * 坑67（P14 探针实证）：单参构造 getFilesystem() 返回 null——必须显式传
 * LocalFilesystem，否则卸载路径静默失败只 WARN。</p>
 */
@Slf4j
@Configuration
public class AssistantWorkspaceConfig {

    @Bean(destroyMethod = "close")
    public WorkspaceManager dcsAssistantWorkspaceManager(
        @Value("${dcs.assistant.compaction.workspace-dir:./data/assistant-workspace}") String workspaceDir) {
        try {
            Path dir = Path.of(workspaceDir);
            Files.createDirectories(dir);
            return new WorkspaceManager(dir, new LocalFilesystem(dir));
        } catch (Exception e) {
            // 目录建不出（磁盘满/权限）=压缩降级（不卸载只摘要），不拦启动
            log.warn("[AssistantWorkspace] 工作目录不可用, 压缩卸载降级: {}", e.getMessage());
            return new WorkspaceManager(Path.of(System.getProperty("java.io.tmpdir"), "dcs-assistant-ws"),
                new LocalFilesystem(Path.of(System.getProperty("java.io.tmpdir"), "dcs-assistant-ws")));
        }
    }
}
