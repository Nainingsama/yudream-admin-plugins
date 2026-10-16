package online.yudream.base.plugin.minecraft.infrastructure.service;

import online.yudream.base.plugin.minecraft.application.service.MinecraftServerAppService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 刷新调度契约：派发不阻塞、按服务器隔离、在跑不重复派发。
 * 历史故障是「调度线程串行探测全部线路，一条挂起即整轮停摆，列表时间戳冻结」。
 */
class MinecraftStatusSchedulerTest {

    @Test
    void dispatchReturnsImmediatelyEvenWhenOneRefreshHangs() throws Exception {
        MinecraftServerAppService appService = mock(MinecraftServerAppService.class);
        when(appService.enabledServerIds()).thenReturn(List.of("slow", "fast"));

        CountDownLatch slowStarted = new CountDownLatch(1);
        CountDownLatch releaseSlow = new CountDownLatch(1);
        CountDownLatch fastDone = new CountDownLatch(1);
        doAnswer(invocation -> {
            slowStarted.countDown();
            releaseSlow.await(10, TimeUnit.SECONDS);
            return null;
        }).when(appService).refreshServerQuietly("slow");
        doAnswer(invocation -> {
            fastDone.countDown();
            return null;
        }).when(appService).refreshServerQuietly("fast");

        MinecraftStatusScheduler scheduler = new MinecraftStatusScheduler(appService);
        scheduler.start();
        try {
            long startedAt = System.currentTimeMillis();
            scheduler.refreshSafely();
            long elapsed = System.currentTimeMillis() - startedAt;

            // 派发立即返回：慢服务器仍在探测中，调度线程不被拖住
            assertTrue(elapsed < 1_000L, "派发耗时应远小于探测耗时，实际 " + elapsed + "ms");
            assertTrue(slowStarted.await(5, TimeUnit.SECONDS), "慢服务器应已开始刷新");
            // 同一轮里另一台不被慢的那台阻塞：时间戳各自前进
            assertTrue(fastDone.await(5, TimeUnit.SECONDS), "快服务器不应被慢服务器阻塞");
        } finally {
            releaseSlow.countDown();
            scheduler.close();
        }
    }

    @Test
    void inFlightServerIsNotDispatchedTwice() throws Exception {
        MinecraftServerAppService appService = mock(MinecraftServerAppService.class);
        when(appService.enabledServerIds()).thenReturn(List.of("only"));

        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            started.countDown();
            release.await(10, TimeUnit.SECONDS);
            return null;
        }).when(appService).refreshServerQuietly("only");

        MinecraftStatusScheduler scheduler = new MinecraftStatusScheduler(appService);
        scheduler.start();
        try {
            scheduler.refreshSafely();
            assertTrue(started.await(5, TimeUnit.SECONDS));
            // 上一轮未跑完：本轮跳过派发，避免任务堆积
            scheduler.refreshSafely();
            assertEquals(List.of("only"), scheduler.inFlight());
            verify(appService, times(1)).refreshServerQuietly("only");
        } finally {
            release.countDown();
            scheduler.close();
        }
        verify(appService, timeout(5000).times(1)).refreshServerQuietly("only");
    }

    @Test
    void refreshErrorsDoNotEscapeDispatchOrKillThePool() {
        MinecraftServerAppService appService = mock(MinecraftServerAppService.class);
        when(appService.enabledServerIds()).thenReturn(List.of("boom"));
        // Error 与 RuntimeException 都在单台刷新内收敛，不穿透派发线程
        doThrow(new AssertionError("mcping 类加载失败")).when(appService).refreshServerQuietly("boom");

        MinecraftStatusScheduler scheduler = new MinecraftStatusScheduler(appService);
        scheduler.start();
        try {
            scheduler.refreshSafely();
            // 池未被击穿：下一轮仍能派发
            verify(appService, timeout(5000).times(1)).refreshServerQuietly("boom");
        } finally {
            scheduler.close();
        }
    }

    @Test
    void dispatchFailureDoesNotThrow() {
        MinecraftServerAppService appService = mock(MinecraftServerAppService.class);
        when(appService.enabledServerIds()).thenThrow(new IllegalStateException("存储不可用"));
        MinecraftStatusScheduler scheduler = new MinecraftStatusScheduler(appService);
        scheduler.start();
        try {
            scheduler.refreshSafely();
            verify(appService, never()).refreshServerQuietly(anyString());
        } finally {
            scheduler.close();
        }
    }
}
