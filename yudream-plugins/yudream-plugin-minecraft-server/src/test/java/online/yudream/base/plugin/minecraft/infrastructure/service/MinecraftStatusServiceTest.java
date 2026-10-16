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

    @Test
    void failureReasonAppendsDialTargetWhenKnown() {
        String reason = MinecraftStatusService.failureReason(
                new ConnectException("Connection refused"), "mc.example.com → 203.0.113.7:41000");
        // 玩家能连、面板连不上时，靠这段拨号目标比对两侧解析差异
        assertTrue(reason.contains("面板拨号目标 mc.example.com → 203.0.113.7:41000"));
    }

    @Test
    void describeTargetHandlesLiteralIpAndHostname() {
        // 字面 IP 不重复展示箭头
        assertEquals("127.0.0.1:25565", MinecraftStatusService.describeTarget("127.0.0.1", 25565));
        // 域名解析成 IP 时以箭头展示解析结果（localhost 恒定可解析）
        assertTrue(MinecraftStatusService.describeTarget("localhost", 41000).startsWith("localhost → "));
        // 无法解析的域名不抛异常，标注未解析
        assertTrue(MinecraftStatusService.describeTarget("no-such-host.invalid", 41000).contains("未解析出 IP"));
    }
}
