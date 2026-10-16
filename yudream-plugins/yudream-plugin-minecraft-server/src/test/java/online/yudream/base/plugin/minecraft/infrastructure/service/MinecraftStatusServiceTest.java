package online.yudream.base.plugin.minecraft.infrastructure.service;

import org.junit.jupiter.api.Test;

import java.net.ConnectException;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 离线原因提取：真因在异常链末端，不能被库的通用包装文案盖住。 */
class MinecraftStatusServiceTest {

    @Test
    void failureReasonUnwrapsLibraryWrapperToRootCause() {
        // mcping 典型形态：RuntimeException("Unhandled exception during ping") → 真因内层
        RuntimeException wrapped = new RuntimeException("Unhandled exception during ping",
                new ConnectException("Connection refused: mc.example.com:41001"));
        assertEquals("Connection refused: mc.example.com:41001", MinecraftStatusService.failureReason(wrapped));
    }

    @Test
    void failureReasonUnwrapsMultiLevelChain() {
        RuntimeException outer = new RuntimeException("Unhandled exception during ping",
                new IllegalStateException("握手失败", new TimeoutException("connect timed out")));
        assertEquals("connect timed out", MinecraftStatusService.failureReason(outer));
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
