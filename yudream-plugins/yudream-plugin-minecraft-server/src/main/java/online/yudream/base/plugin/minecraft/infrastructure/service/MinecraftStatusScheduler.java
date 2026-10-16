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
 * 状态刷新调度：调度与探测解耦、按服务器隔离、并由自愈看门狗兜底。
 *
 * <p>历史故障：每周期在调度线程里串行探测全部服务器与线路，只要一条线路的探测不返回
 * （DNS 无 OS 级超时、socket 挂起），整轮刷新就停在那里，列表「上次检测」时间不再前进
 * （异常已被吞掉，阻塞却吞不掉）。
 *
 * <p>现在的分层：
 * <ul>
 *   <li>调度线程只派发（每 {@code refreshIntervalMs} 一轮），立即返回，不被探测拖住；</li>
 *   <li>每台服务器一个独立刷新任务（有界并发池），单台挂起只让自己这台的快照变旧；</li>
 *   <li>同一台上一轮未跑完时跳过本轮派发，任务不堆积；</li>
 *   <li>看门狗（独立线程、只做时间判断）发现派发长时间未执行（调度线程被存储层等
 *       非探测因素卡住）时打印日志并重建调度与线程池——无论卡在哪，刷新都能恢复。</li>
 * </ul>
 */
public class MinecraftStatusScheduler implements AutoCloseable {

    private static final long INITIAL_DELAY_MS = TimeUnit.SECONDS.toMillis(30);
    private static final long REFRESH_INTERVAL_MS = TimeUnit.MINUTES.toMillis(1);
    /** 派发停摆判定：超过该时长未派发即视为停摆并重建（容 3 个刷新周期）。 */
    private static final long STALE_AFTER_MS = TimeUnit.MINUTES.toMillis(3);
    private static final long WATCHDOG_INTERVAL_MS = TimeUnit.MINUTES.toMillis(1);

    /** 刷新派发并发上限：够覆盖常规服务器数量，又不至于同时打出过多探测。 */
    private static final int REFRESH_POOL_SIZE = 4;

    private final MinecraftServerAppService appService;
    private final long initialDelayMs;
    private final long refreshIntervalMs;
    private final long staleAfterMs;
    private final long watchdogIntervalMs;

    /** 在跑中的服务器（幂等键）：上一轮未完成时跳过本轮派发。 */
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    /** 最近一次派发开始时间：派发线程本身卡住时该值停止前进，看门狗据此判定停摆。 */
    private volatile long lastDispatchAtMs;

    private ScheduledExecutorService executor;
    private ScheduledExecutorService watchdog;
    private ExecutorService refreshPool;

    public MinecraftStatusScheduler(MinecraftServerAppService appService) {
        this(appService, INITIAL_DELAY_MS, REFRESH_INTERVAL_MS, STALE_AFTER_MS, WATCHDOG_INTERVAL_MS);
    }

    /** 供测试注入更短周期。 */
    MinecraftStatusScheduler(MinecraftServerAppService appService, long initialDelayMs, long refreshIntervalMs,
                             long staleAfterMs, long watchdogIntervalMs) {
        this.appService = appService;
        this.initialDelayMs = initialDelayMs;
        this.refreshIntervalMs = refreshIntervalMs;
        this.staleAfterMs = staleAfterMs;
        this.watchdogIntervalMs = watchdogIntervalMs;
    }

    public synchronized void start() {
        if (executor != null) {
            return;
        }
        createRuntime();
    }

    private void createRuntime() {
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
        // 以派发时刻为基准（首次执行即写入），此后每 refreshIntervalMs 一轮
        lastDispatchAtMs = System.currentTimeMillis();
        executor.scheduleWithFixedDelay(this::refreshSafely, initialDelayMs, refreshIntervalMs, TimeUnit.MILLISECONDS);
        if (watchdog == null) {
            watchdog = Executors.newSingleThreadScheduledExecutor(task -> {
                Thread thread = new Thread(task, "minecraft-status-watchdog");
                thread.setDaemon(true);
                return thread;
            });
            watchdog.scheduleWithFixedDelay(this::watchdogTick, watchdogIntervalMs, watchdogIntervalMs, TimeUnit.MILLISECONDS);
        }
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
        if (watchdog != null) {
            watchdog.shutdownNow();
            watchdog = null;
        }
        inFlight.clear();
    }

    /**
     * 派发一轮刷新：只提交任务、立即返回，调度线程永不被探测拖住；吞掉一切 Throwable
     * （含 Error），避免周期任务被取消后状态永久冻结。
     */
    void refreshSafely() {
        lastDispatchAtMs = System.currentTimeMillis();
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

    /** 看门狗：派发长时间未发生即判定停摆（调度线程被非探测因素卡住），重建调度与线程池。 */
    void watchdogTick() {
        long last = lastDispatchAtMs;
        long idleMs = System.currentTimeMillis() - last;
        if (last <= 0 || idleMs <= staleAfterMs) {
            return;
        }
        System.err.println("[minecraft-server] 状态刷新派发已停摆 " + idleMs + "ms（阈值 " + staleAfterMs
                + "ms）：重建刷新调度线程，恢复状态刷新");
        rebuildRuntime();
    }

    private synchronized void rebuildRuntime() {
        if (executor == null) {
            return;
        }
        executor.shutdownNow();
        if (refreshPool != null) {
            refreshPool.shutdownNow();
        }
        inFlight.clear();
        createRuntime();
    }

    /** 在跑中的服务器快照（诊断/测试用）。 */
    List<String> inFlight() {
        return List.copyOf(inFlight);
    }

    /** 最近一次派发时间（诊断/测试用）。 */
    long lastDispatchAtMs() {
        return lastDispatchAtMs;
    }
}
