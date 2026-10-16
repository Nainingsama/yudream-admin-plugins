package online.yudream.base.plugin.minecraft.infrastructure.service;

import online.yudream.base.plugin.minecraft.application.service.MinecraftServerAppService;
import online.yudream.base.plugin.minecraft.domain.aggregate.MinecraftServer;
import online.yudream.base.plugin.minecraft.domain.repo.MinecraftServerRepository;
import online.yudream.base.plugin.minecraft.domain.valobj.MinecraftServerEndpoint;
import online.yudream.base.plugin.spi.core.PluginContext;
import online.yudream.base.plugin.spi.system.FrameworkServices;
import online.yudream.base.plugin.spi.system.storage.PluginFileStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 状态调度加固：周期任务抛出 Error（类加载/链接失败等）不得穿透——
 * scheduleWithFixedDelay 的任务一旦抛出未捕获异常即被永久取消，此后列表状态
 * 冻结在上次结果（服务器恢复在线也恒显离线），且无任何报错痕迹。
 */
class MinecraftStatusSchedulerTest {

    @Test
    void refreshSafelySwallowsErrorsSoTheLoopSurvives() {
        MinecraftServerRepository repository = mock(MinecraftServerRepository.class);
        MinecraftServer server = mock(MinecraftServer.class);
        MinecraftServerEndpoint endpoint = new MinecraftServerEndpoint(
                "line-1", "主线", "play.example.com", 25565, null, true, true, 0);
        when(server.id()).thenReturn("server-1");
        when(server.endpoints()).thenReturn(List.of(endpoint));
        when(repository.list(anyInt(), anyInt(), anyBoolean())).thenReturn(List.of(server));
        when(repository.findById("server-1")).thenReturn(Optional.of(server));

        MinecraftStatusService statusService = mock(MinecraftStatusService.class);
        // 线路探测抛出 Error（而非 RuntimeException）：旧实现只捕获 RuntimeException，任务会被永久取消
        when(statusService.ping(any())).thenThrow(new AssertionError("mcping 类加载失败"));

        PluginContext context = mock(PluginContext.class);
        when(context.framework()).thenReturn(mock(FrameworkServices.class));
        when(context.files()).thenReturn(mock(PluginFileStore.class));
        MinecraftServerAppService appService = new MinecraftServerAppService(repository, statusService, context);

        MinecraftStatusScheduler scheduler = new MinecraftStatusScheduler(appService);

        assertDoesNotThrow(scheduler::refreshSafely);
    }

    @Test
    void refreshSafelySwallowsRuntimeExceptions() {
        MinecraftServerRepository repository = mock(MinecraftServerRepository.class);
        when(repository.list(anyInt(), anyInt(), anyBoolean()))
                .thenThrow(new IllegalStateException("存储不可用"));
        PluginContext context = mock(PluginContext.class);
        when(context.framework()).thenReturn(mock(FrameworkServices.class));
        when(context.files()).thenReturn(mock(PluginFileStore.class));
        MinecraftServerAppService appService =
                new MinecraftServerAppService(repository, mock(MinecraftStatusService.class), context);

        MinecraftStatusScheduler scheduler = new MinecraftStatusScheduler(appService);

        assertDoesNotThrow(scheduler::refreshSafely);
    }
}
