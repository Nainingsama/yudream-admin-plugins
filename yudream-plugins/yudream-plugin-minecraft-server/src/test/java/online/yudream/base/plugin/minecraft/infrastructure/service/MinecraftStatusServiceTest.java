package online.yudream.base.plugin.minecraft.infrastructure.service;

import org.junit.jupiter.api.Test;

import java.net.ConnectException;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 离线原因提取：真因在异常链末端，不能被库的通用包装文案盖住。 */
class MinecraftStatusServiceTest {

    @Test
    void failureReasonUnwrapsLibraryWrapperToRootCause() {
        // mcping 典型形态：RuntimeException("Unhandled exception during ping") → 真因内层
        RuntimeException wrapped = new RuntimeException("Unhandled exception during ping",
                new ConnectException("Connection refused: mc.example.com:41001"));
        String reason = MinecraftStatusService.failureReason(wrapped);
        // 原始文案保留，并补上处置提示（连接被拒 → 端口无监听/防火墙拒绝）
        assertTrue(reason.startsWith("Connection refused: mc.example.com:41001"));
        assertTrue(reason.contains("连接被拒绝"));
        assertTrue(reason.contains("防火墙"));
    }

    @Test
    void failureReasonHintsTimeoutAndDns() {
        String timeout = MinecraftStatusService.failureReason(new RuntimeException("Unhandled exception during ping",
                new java.net.SocketTimeoutException("connect timed out")));
        assertTrue(timeout.contains("连接超时"));

        String dns = MinecraftStatusService.failureReason(new java.net.UnknownHostException("fksk.mc.example.com"));
        assertTrue(dns.contains("域名解析失败"));
    }

    @Test
    void failureReasonUnwrapsMultiLevelChain() {
        RuntimeException outer = new RuntimeException("Unhandled exception during ping",
                new IllegalStateException("握手失败", new TimeoutException("connect timed out")));
        assertTrue(MinecraftStatusService.failureReason(outer).startsWith("connect timed out"));
    }

    @Test
    void failureReasonFallsBackToClassNameWhenMessageBlank() {
        assertEquals("IllegalStateException", MinecraftStatusService.failureReason(new IllegalStateException()));
    }

    @Test
    void failureReasonToleratesSelfReferencingCause() {
        RuntimeException self = new RuntimeException("自引用") {
            @Override
            public synchronized Throwable getCause() {
                return this;
            }
        };
        assertEquals("自引用", MinecraftStatusService.failureReason(self));
    }
}
