package online.yudream.base.plugin.minecraft.infrastructure.service;

import online.yudream.base.plugin.minecraft.application.service.MinecraftServerAppService;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class MinecraftStatusScheduler implements AutoCloseable {

    private static final long INITIAL_DELAY_SECONDS = 30;
    private static final long REFRESH_INTERVAL_MINUTES = 1;

    private final MinecraftServerAppService appService;
    private ScheduledExecutorService executor;

    public MinecraftStatusScheduler(MinecraftServerAppService appService) {
        this.appService = appService;
    }

    public synchronized void start() {
        if (executor != null) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "minecraft-status-refresh");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(this::refreshSafely, INITIAL_DELAY_SECONDS, REFRESH_INTERVAL_MINUTES, TimeUnit.MINUTES);
    }

    @Override
    public synchronized void close() {
        if (executor == null) {
            return;
        }
        executor.shutdownNow();
        executor = null;
    }

    /**
     * 周期刷新入口：必须吞掉一切 Throwable——scheduleWithFixedDelay 的任务一旦抛出
     * 未捕获异常（含 Error）即被永久取消，此后状态冻结在上次结果（服务器恢复在线
     * 列表也恒显离线），且无任何报错痕迹。
     */
    void refreshSafely() {
        try {
            appService.refreshEnabledServers();
        } catch (RuntimeException | Error error) {
            System.err.println("[minecraft-server] 状态周期刷新失败: " + error);
        }
    }
}
