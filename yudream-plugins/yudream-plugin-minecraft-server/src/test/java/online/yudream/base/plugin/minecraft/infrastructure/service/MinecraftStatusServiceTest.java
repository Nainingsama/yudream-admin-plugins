package online.yudream.base.plugin.minecraft.infrastructure.service;

import online.yudream.base.plugin.minecraft.domain.valobj.MinecraftEndpointStatus;
import online.yudream.base.plugin.minecraft.domain.valobj.MinecraftServerEndpoint;
import org.junit.jupiter.api.Test;

import java.net.ConnectException;
import java.util.List;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
    void pingFallsBackToOfflineWithinDeadlineWhenProbeHangs() {
        MinecraftServerEndpoint hanging = new MinecraftServerEndpoint(
                "line-hang", "主线", "10.255.255.1", 41000, null, true, true, 0);
        try (MinecraftStatusService service = new MinecraftStatusService()) {
            long startedAt = System.currentTimeMillis();
            MinecraftEndpointStatus status = service.ping(hanging);
            long elapsed = System.currentTimeMillis() - startedAt;

            // 探测不可达地址：要么按连接失败快速返回，要么在硬上限内按超时返回，绝不无限等待
            assertEquals("OFFLINE", status.status());
            assertNotNull(status.errorMessage());
            // 上限 = 主探测硬上限 + 兜底候选总预算（两者皆为有界值，不允许无限等待）
            long bound = MinecraftStatusService.PROBE_DEADLINE_MS + MinecraftStatusService.FALLBACK_BUDGET_MS + 4_000L;
            assertTrue(elapsed <= bound, "探测必须在有界时间内返回，实际 " + elapsed + "ms（上限 " + bound + "ms）");
        }
    }

    @Test
    void describeTargetHandlesLiteralIpAndHostname() {
        try (MinecraftStatusService service = new MinecraftStatusService()) {
            // 字面 IP 不重复展示箭头
            assertEquals("127.0.0.1:25565", service.describeTarget("127.0.0.1", 25565));
            // 域名解析成 IP 时以箭头展示解析结果（localhost 恒定可解析）
            assertTrue(service.describeTarget("localhost", 41000).startsWith("localhost → "));
            // 无法解析的域名不抛异常，标注未解析
            assertTrue(service.describeTarget("no-such-host.invalid", 41000).contains("未解析出 IP"));
        }
    }

    @Test
    void loopbackCandidatesCoverSameHostDeployments() {
        try (MinecraftStatusService service = new MinecraftStatusService()) {
            List<String> candidates = service.loopbackCandidates("mc.example.com");
            // 同机部署时面板经公网地址连不上自己的游戏端口：回环与 Docker 别名必须在内
            assertTrue(candidates.contains("127.0.0.1"), "应包含回环地址");
            assertTrue(candidates.contains("host.docker.internal"), "应包含 Docker Desktop 别名");
            // 候选有界且不含配置地址本身（配置地址由主探测负责）
            assertTrue(candidates.size() <= 6, "候选数量应有界，实际 " + candidates.size());
            assertFalse(candidates.contains("mc.example.com"));
        }
    }

    @Test
    void preferredHostUsesConfiguredHostUntilFallbackSucceeds() {
        MinecraftServerEndpoint endpoint = new MinecraftServerEndpoint(
                "line-1", "主线", "mc.example.com", 41001, null, true, true, 0);
        try (MinecraftStatusService service = new MinecraftStatusService()) {
            assertEquals("mc.example.com", service.preferredHost(endpoint));
        }
    }
}
