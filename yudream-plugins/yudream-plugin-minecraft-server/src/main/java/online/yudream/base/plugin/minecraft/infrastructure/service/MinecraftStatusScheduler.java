package online.yudream.base.plugin.minecraft.infrastructure.service;

import online.yudream.base.plugin.minecraft.application.service.MinecraftServerAppService;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 状态刷新调度：调度与探测解耦、按服务器隔离。
 *
 * <p>历史形态是「每周期在调度线程里串行探测全部服务器与线路」——只要有一条线路的
 * 探测不返回（DNS 无 OS 级超时、socket 挂起），整轮刷新就停在那里，列表的
 * 「上次检测」时间不再前进（异常已被吞掉，阻塞却吞不掉）。现在调度线程只做派发：
 * 每台服务器一个独立任务（有界并发池），单台挂起只让自己这台的时间戳变旧，
 * 其余服务器照常刷新；同一台在上一轮未跑完时跳过本轮派发，不排队堆积。
 */
public class MinecraftStatusScheduler implements AutoCloseable {

    private static final long INITIAL_DELAY_SECONDS = 30;
    private static final long REFRESH_INTERVAL_MINUTES = 1;

    /** 刷新派发并发上限：够覆盖常规服务器数量，又不至于同时打出过多探测。 */
    private static final int REFRESH_POOL_SIZE = 4;

    private final MinecraftServerAppService appService;
    /** 在跑中的服务器（幂等键）：上一轮未完成时跳过本轮派发。 */
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    private ScheduledExecutorService executor;
    private ExecutorService refreshPool;

    public MinecraftStatusScheduler(MinecraftServerAppService appService) {
        this.appService = appService;
    }

    public synchronized void start() {
        if (executor != null) {
            return;
        }
        refreshPool = Executors.newFixedThreadPool(REFRESH_POOL_SIZE, runnable -> {
            Thread thread = new Thread(runnable, "minecraft-status-refresh");
            thread.setDaemon(true);
            return thread;
        });
        executor = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "minecraft-status-dispatch");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(this::refreshSafely, INITIAL_DELAY_SECONDS, REFRESH_INTERVAL_MINUTES, TimeUnit.MINUTES);
    }

    @Override
    public synchronized void close() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        if (refreshPool != null) {
            refreshPool.shutdownNow();
            refreshPool = null;
        }
        inFlight.clear();
    }

    /**
     * 派发一轮刷新：只提交任务、立即返回，调度线程永不被探测拖住；吞掉一切 Throwable
     * （含 Error），避免周期任务被取消后状态永久冻结。
     */
    void refreshSafely() {
        try {
            for (String serverId : appService.enabledServerIds()) {
                if (serverId == null || serverId.isBlank() || !inFlight.add(serverId)) {
                    continue;
                }
                refreshPool.execute(() -> {
                    try {
                        appService.refreshServerQuietly(serverId);
                    } finally {
                        inFlight.remove(serverId);
                    }
                });
            }
        } catch (RuntimeException | Error error) {
            System.err.println("[minecraft-server] 状态刷新派发失败: " + error);
        }
    }

    /** 在跑中的服务器快照（诊断/测试用）。 */
    List<String> inFlight() {
        return List.copyOf(inFlight);
    }
}
