package online.yudream.base.plugin.minecraft.infrastructure.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.lenni0451.mcping.MCPing;
import net.lenni0451.mcping.responses.BedrockPingResponse;
import net.lenni0451.mcping.responses.MCPingResponse;
import online.yudream.base.plugin.minecraft.domain.enumerate.MinecraftEdition;
import online.yudream.base.plugin.minecraft.domain.valobj.MinecraftEndpointStatus;
import online.yudream.base.plugin.minecraft.domain.valobj.MinecraftServerEndpoint;

import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.InitialDirContext;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class MinecraftStatusService implements AutoCloseable {

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * 单线路探测硬上限：ping 库在 DNS 挂起（解析不可达且无 OS 级超时）等场景可能长时间
     * 不返回，而状态刷新是单线程每周期串行探测全部线路——一次挂起就会让整轮刷新停摆，
     * 列表的「上次检测」时间随之冻结。超限即按离线落快照，宁可报一次探测超时。
     */
    static final long PROBE_DEADLINE_MS = 8_000L;

    /** 探测执行池：给每次探测套硬上限用；卡死的探测被取消后不影响后续探测。 */
    private final ExecutorService probePool = Executors.newFixedThreadPool(6, runnable -> {
        Thread thread = new Thread(runnable, "minecraft-status-probe");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * 解析执行池：与探测池分开——解析调用发生在探测线程内部，若共用同一个池，
     * 探测线程等待自己提交的解析任务会互相占位（极端情况下集体饿死）。
     */
    private final ExecutorService resolvePool = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "minecraft-status-resolve");
        thread.setDaemon(true);
        return thread;
    });

    /** 域名解析缓存：解析本身可能阻塞，既要有界也要避免每条线路每轮重复解析。 */
    private final ConcurrentHashMap<String, ResolvedIp> resolvedIps = new ConcurrentHashMap<>();

    private static final long RESOLVED_TTL_MS = 10 * 60 * 1000L;
    private static final long UNRESOLVED_TTL_MS = 60 * 1000L;
    private static final long RESOLVE_DEADLINE_MS = 1_500L;

    /**
     * 兜底探测地址缓存（端点 → 主机）：面板与节点同机部署时，面板经公网地址访问自己的
     * 游戏端口常被网关的 NAT 回环策略拒绝（玩家从外网访问正常），此时改用本机/网关地址
     * 才能探测成功。命中的兜底地址记住一段时间，后续周期直接用它，避免每轮重试候选。
     */
    private final ConcurrentHashMap<String, String> reachedHosts = new ConcurrentHashMap<>();

    private static final long REACHED_TTL_MS = 10 * 60 * 1000L;

    private record ResolvedIp(String value, long expiresAtMs) {
    }

    /** 兜底候选地址（同机部署的可达路径）：回环、Docker Desktop 别名、本机网段网关与本机地址。 */
    static final List<String> LOOPBACK_CANDIDATES = List.of("127.0.0.1", "host.docker.internal");

    @Override
    public void close() {
        probePool.shutdownNow();
        resolvePool.shutdownNow();
    }

    public MinecraftEndpointStatus ping(MinecraftServerEndpoint endpoint) {
        if (!endpoint.enabled()) {
            return MinecraftEndpointStatus.offline(endpoint.id(), "线路已停用");
        }
        String configured = endpoint.host();
        String preferred = preferredHost(endpoint);
        MinecraftEndpointStatus primary = probeWithDeadline(endpoint, preferred, PROBE_DEADLINE_MS);
        if ("ONLINE".equals(primary.status())) {
            reachedHosts.put(endpoint.id(), preferred);
            return primary;
        }
        // 公网地址不可达（同机部署的 NAT 回环不通、仅外网放行等）：依次尝试本机/网关地址。
        // 兜底总耗时另设预算：候选逐个串行，不能把一条线路的探测拖成分钟级。
        long budgetEnd = System.currentTimeMillis() + FALLBACK_BUDGET_MS;
        for (String candidate : loopbackCandidates(configured)) {
            long remaining = budgetEnd - System.currentTimeMillis();
            if (remaining <= 0) {
                break;
            }
            MinecraftEndpointStatus fallback = probeWithDeadline(endpoint, candidate,
                    Math.min(FALLBACK_DEADLINE_MS, remaining));
            if ("ONLINE".equals(fallback.status())) {
                reachedHosts.put(endpoint.id(), candidate);
                return fallback;
            }
        }
        // 全部不可达：报配置地址的失败原因（含实际拨号目标），保持运维可见
        return primary;
    }

    /** 当前优先使用的主机：已确认可达的兜底地址（带 TTL）优先，否则用配置地址。 */
    String preferredHost(MinecraftServerEndpoint endpoint) {
        String cached = reachedHosts.get(endpoint.id());
        return cached == null || cached.isBlank() ? endpoint.host() : cached;
    }

    /**
     * 同机部署的兜底候选：回环地址、Docker Desktop 别名，以及本机各网段的 .1 网关
     * （容器默认桥与自定义桥都把宿主配成 .1，游戏端口发布在宿主上，经网关可达）。
     */
    List<String> loopbackCandidates(String configuredHost) {
        List<String> candidates = new ArrayList<>(LOOPBACK_CANDIDATES);
        try {
            java.util.Enumeration<java.net.NetworkInterface> interfaces = java.net.NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                java.net.NetworkInterface network = interfaces.nextElement();
                if (!network.isUp() || network.isLoopback()) {
                    continue;
                }
                for (java.net.InterfaceAddress address : network.getInterfaceAddresses()) {
                    java.net.InetAddress local = address.getAddress();
                    if (local == null || local.getAddress() == null || local.getAddress().length != 4) {
                        continue;
                    }
                    String host = local.getHostAddress();
                    if (host != null && !host.isBlank()) {
                        candidates.add(host);
                    }
                    byte[] gateway = local.getAddress().clone();
                    gateway[3] = 1;
                    candidates.add(java.net.InetAddress.getByAddress(gateway).getHostAddress());
                }
            }
        } catch (java.net.SocketException | java.net.UnknownHostException ignored) {
            // 网卡枚举失败：仅用固定候选（回环/Docker 别名）
        }
        List<String> unique = new ArrayList<>();
        for (String candidate : candidates) {
            if (candidate == null || candidate.isBlank() || candidate.equals(configuredHost) || unique.contains(candidate)) {
                continue;
            }
            unique.add(candidate);
            if (unique.size() >= MAX_FALLBACK_CANDIDATES) {
                break;
            }
        }
        return unique;
    }

    private static final long FALLBACK_DEADLINE_MS = 3_000L;
    /** 兜底候选总预算：候选串行尝试，整体不超过该时长。 */
    static final long FALLBACK_BUDGET_MS = 6_000L;
    private static final int MAX_FALLBACK_CANDIDATES = 6;

    /** 以指定主机探测一次（单次探测套硬上限，卡死不影响调用方）。 */
    private MinecraftEndpointStatus probeWithDeadline(MinecraftServerEndpoint endpoint, String host, long deadlineMs) {
        ProbeAttempt attempt = new ProbeAttempt();
        Future<MinecraftEndpointStatus> probe = probePool.submit(() -> probeEndpoint(endpoint, host, attempt));
        try {
            return probe.get(deadlineMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException hang) {
            probe.cancel(true);
            return MinecraftEndpointStatus.offline(endpoint.id(),
                    "探测超时（" + deadlineMs / 1000 + " 秒无响应）（连接超时：被防火墙/安全组丢包，或服务端未响应）"
                            + targetSuffix(attempt.target));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return MinecraftEndpointStatus.offline(endpoint.id(), "探测被中断" + targetSuffix(attempt.target));
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause() == null ? failed : failed.getCause();
            return MinecraftEndpointStatus.offline(endpoint.id(), failureReason(cause, attempt.target));
        }
    }

    /** 探测尝试的拨号目标（由探测线程写、等待方读，故用 volatile 持有）。 */
    private static final class ProbeAttempt {
        private volatile String target;
    }

    private MinecraftEndpointStatus probeEndpoint(MinecraftServerEndpoint endpoint, String host, ProbeAttempt attempt) {
        String dialHost = host == null || host.isBlank() ? endpoint.host() : host;
        try {
            if (endpoint.edition() == MinecraftEdition.BEDROCK) {
                attempt.target = describeTarget(dialHost, endpoint.port());
                BedrockPingResponse response = MCPing.pingBedrock()
                        .address(dialHost, endpoint.port())
                        .timeout(3000, 3000)
                        .getSync();
                return MinecraftEndpointStatus.online(endpoint.id(), response.getOnlinePlayers(), response.getMaxPlayers(),
                        plainText(response.getVersionName()), response.getProtocolId(), response.getPing(), plainMotd(response.getMotd()), null,
                        List.of());
            }
            ResolvedAddress address = resolveJavaAddress(endpoint, dialHost);
            attempt.target = describeTarget(address.host(), address.port());
            MCPingResponse response = MCPing.pingModern()
                    .address(address.host(), address.port())
                    .timeout(3000, 3000)
                    .getSync();
            return MinecraftEndpointStatus.online(endpoint.id(), response.getOnlinePlayers(), response.getMaxPlayers(),
                    plainText(response.getVersionName()), response.getProtocolId(), response.getPing(), plainMotd(response.getMotd()),
                    favicon(response.getFavicon()), samplePlayers(response));
        } catch (RuntimeException e) {
            return MinecraftEndpointStatus.offline(endpoint.id(), failureReason(e, attempt.target));
        } catch (Error e) {
            // ping 库的类加载/链接失败等 Error 也按该线路离线落快照，不允许穿透杀死状态调度循环
            System.err.println("[minecraft-server] 线路探测异常 " + dialHost + ":" + endpoint.port() + ": " + e);
            return MinecraftEndpointStatus.offline(endpoint.id(), failureReason(e, attempt.target));
        }
    }

    private static String targetSuffix(String target) {
        return target == null || target.isBlank() ? "" : "（面板拨号目标 " + target + "）";
    }

    /**
     * 面板实际拨号目标：域名解析结果（含 SRV 重定向后的目标）。玩家侧能连而面板侧
     * 连不上时，第一嫌疑就是两侧解析到的地址不同（内网分割 DNS、显式端口与 SRV 不一致），
     * 把解析结果带进提示即可一眼比对。解析带 1.5 秒上限并缓存，避免拖慢探测。
     */
    String describeTarget(String host, int port) {
        if (host == null || host.isBlank()) {
            return "";
        }
        String ip = resolveIp(host);
        return ip == null ? host + ":" + port + "（未解析出 IP）"
                : ip.equals(host) ? host + ":" + port : host + " → " + ip + ":" + port;
    }

    /** 有界且带缓存的域名解析：解析失败按短 TTL 缓存，避免每轮重复阻塞。 */
    private String resolveIp(String host) {
        long now = System.currentTimeMillis();
        ResolvedIp cached = resolvedIps.get(host);
        if (cached != null && cached.expiresAtMs() > now) {
            return cached.value();
        }
        Callable<String> lookup = () -> {
            try {
                return java.net.InetAddress.getByName(host).getHostAddress();
            } catch (java.net.UnknownHostException unresolved) {
                return null;
            }
        };
        Future<String> future = resolvePool.submit(lookup);
        String value;
        try {
            value = future.get(RESOLVE_DEADLINE_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return null;
        } catch (TimeoutException | ExecutionException indeterminate) {
            // 解析未在时限内返回：不缓存、不等待，本轮不展示解析结果
            future.cancel(true);
            return null;
        }
        resolvedIps.put(host, new ResolvedIp(value, now + (value == null ? UNRESOLVED_TTL_MS : RESOLVED_TTL_MS)));
        return value;
    }

    /**
     * 离线原因取异常链末端根因：mcping 等库习惯用 "Unhandled exception during ping"
     * 之类的通用文案包一层，真因（连接被拒 / 超时 / 协议不支持）只存在于 cause 链末端，
     * 只看顶层消息无从定位。常见网络失败再补一句中文处置提示，并附面板实际拨号目标。
     */
    static String failureReason(Throwable error) {
        return failureReason(error, null);
    }

    static String failureReason(Throwable error, String target) {
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        String reason = message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
        String suffix = target == null || target.isBlank() ? "" : "（面板拨号目标 " + target + "）";
        return reason + hint(cause, reason) + suffix;
    }

    /** 常见网络失败的处置提示（原始文案保留，提示只作补充）。 */
    private static String hint(Throwable cause, String reason) {
        String lowered = reason.toLowerCase(java.util.Locale.ROOT);
        if (lowered.contains("refused")) {
            return "（连接被拒绝：该地址端口没有服务监听，或被防火墙/安全组拒绝）";
        }
        if (lowered.contains("timed out") || lowered.contains("timeout")) {
            return "（连接超时：被防火墙/安全组丢包，或服务端未响应）";
        }
        if (cause instanceof java.net.UnknownHostException
                || lowered.contains("unknownhost") || lowered.contains("no such host")
                || lowered.contains("name or service not known")) {
            return "（域名解析失败：检查域名拼写与面板宿主的 DNS）";
        }
        return "";
    }

    private static List<MinecraftEndpointStatus.PlayerInfo> samplePlayers(MCPingResponse response) {
        if (response.players == null || response.players.sample == null) {
            return List.of();
        }
        List<MinecraftEndpointStatus.PlayerInfo> players = new ArrayList<>();
        for (MCPingResponse.Players.Player player : response.players.sample) {
            if (player == null || player.name == null || player.name.isBlank()) {
                continue;
            }
            players.add(new MinecraftEndpointStatus.PlayerInfo(player.id, player.name));
            if (players.size() >= MinecraftEndpointStatus.MAX_SAMPLED_PLAYERS) {
                break;
            }
        }
        return players;
    }

    private static String favicon(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        return normalized.regionMatches(true, 0, "data:image/png;base64,", 0, "data:image/png;base64,".length())
                ? normalized : null;
    }

    public static String plainMotd(String motd) {
        return plainText(motd);
    }

    public static String plainText(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        String text = value.trim();
        if (text.startsWith("{") || text.startsWith("[")) {
            try {
                StringBuilder builder = new StringBuilder();
                appendComponentText(JSON.readTree(text), builder);
                if (!builder.isEmpty()) {
                    text = builder.toString();
                }
            } catch (Exception ignored) {
                // Some servers return malformed components; retain their original text.
            }
        }
        return text.replace("\\u00a7", "§")
                .replaceAll("(?i)(?:§|&)[0-9A-FK-ORX]", "")
                .trim();
    }

    private static void appendComponentText(JsonNode node, StringBuilder text) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isTextual()) {
            text.append(node.asText());
            return;
        }
        if (node.isArray()) {
            node.forEach(item -> appendComponentText(item, text));
            return;
        }
        if (!node.isObject()) {
            return;
        }
        JsonNode content = node.get("text");
        if (content != null && content.isValueNode()) {
            text.append(content.asText());
        }
        JsonNode extra = node.get("extra");
        if (extra != null) {
            appendComponentText(extra, text);
        }
    }

    private ResolvedAddress resolveJavaAddress(MinecraftServerEndpoint endpoint, String host) {
        String dialHost = host == null || host.isBlank() ? endpoint.host() : host;
        if (!endpoint.automaticPort()) {
            return new ResolvedAddress(dialHost, endpoint.port());
        }
        return resolveSrv(dialHost).orElseGet(() -> new ResolvedAddress(dialHost, MinecraftEdition.JAVA.defaultPort()));
    }

    private Optional<ResolvedAddress> resolveSrv(String host) {
        if (host == null || host.isBlank() || isIpLiteral(host)) {
            return Optional.empty();
        }
        try {
            Hashtable<String, String> environment = new Hashtable<>();
            environment.put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory");
            environment.put("com.sun.jndi.dns.timeout.initial", "1500");
            environment.put("com.sun.jndi.dns.timeout.retries", "1");
            Attributes attributes;
            InitialDirContext context = new InitialDirContext(environment);
            try {
                attributes = context.getAttributes("_minecraft._tcp." + host, new String[]{"SRV"});
            } finally {
                try {
                    context.close();
                } catch (NamingException ignored) {
                }
            }
            Attribute srv = attributes.get("SRV");
            if (srv == null || srv.size() == 0) {
                return Optional.empty();
            }
            SrvRecord selected = null;
            for (int i = 0; i < srv.size(); i++) {
                SrvRecord record = SrvRecord.parse(String.valueOf(srv.get(i)));
                if (record != null && better(record, selected)) {
                    selected = record;
                }
            }
            return selected == null ? Optional.empty() : Optional.of(new ResolvedAddress(selected.target(), selected.port()));
        } catch (NamingException e) {
            return Optional.empty();
        }
    }

    private boolean better(SrvRecord record, SrvRecord selected) {
        return selected == null
                || record.priority() < selected.priority()
                || record.priority() == selected.priority() && record.weight() > selected.weight();
    }

    private boolean isIpLiteral(String host) {
        String value = host.trim();
        return value.indexOf(':') >= 0 || value.matches("\\d{1,3}(\\.\\d{1,3}){3}");
    }

    private record ResolvedAddress(String host, int port) {
    }

    private record SrvRecord(int priority, int weight, int port, String target) {

        private static SrvRecord parse(String value) {
            if (value == null) {
                return null;
            }
            String[] parts = value.trim().split("\\s+");
            if (parts.length < 4) {
                return null;
            }
            try {
                if (".".equals(parts[3])) {
                    return null;
                }
                String target = parts[3].endsWith(".") ? parts[3].substring(0, parts[3].length() - 1) : parts[3];
                return new SrvRecord(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), target);
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }
}
