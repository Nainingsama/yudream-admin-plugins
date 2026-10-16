package online.yudream.base.plugin.minecraft.domain.valobj;

import java.util.Comparator;
import java.util.List;

public record MinecraftServerStatus(
        String serverId,
        String status,
        int onlinePlayers,
        int maxPlayers,
        List<MinecraftEndpointStatus> endpoints,
        long checkedAt,
        /** 在线判定的依据：null＝线路探测；"panel"＝线路均不可达但面板确认实例在运行。 */
        String source
) {

    public MinecraftServerStatus {
        endpoints = endpoints == null ? List.of() : List.copyOf(endpoints);
    }

    public static MinecraftServerStatus from(String serverId, List<MinecraftEndpointStatus> endpointStatuses) {
        List<MinecraftEndpointStatus> items = endpointStatuses == null ? List.of() : endpointStatuses;
        int online = items.stream().filter(item -> "ONLINE".equals(item.status()))
                .mapToInt(MinecraftEndpointStatus::onlinePlayers)
                .max()
                .orElse(0);
        int max = items.stream().filter(item -> "ONLINE".equals(item.status()))
                .mapToInt(MinecraftEndpointStatus::maxPlayers)
                .max()
                .orElse(0);
        long checkedAt = items.stream()
                .map(MinecraftEndpointStatus::checkedAt)
                .max(Comparator.naturalOrder())
                .orElse(System.currentTimeMillis());
        String status = items.stream().anyMatch(item -> "ONLINE".equals(item.status())) ? "ONLINE" : "OFFLINE";
        return new MinecraftServerStatus(serverId, status, online, max, items, checkedAt, null);
    }

    /**
     * 面板容器状态兜底：线路探测全部不可达、但面板（mcpanel）确认该服务器绑定的实例
     * 正在运行——同机部署时面板常因网关 NAT 回环策略连不上自己的游戏端口，此时按容器
     * 状态判在线（玩家计数未知，置 0）；线路各自的失败原因仍保留在 endpoints 里。
     */
    public static MinecraftServerStatus fromPanel(MinecraftServerStatus probed) {
        return new MinecraftServerStatus(probed.serverId(), "ONLINE", 0, 0,
                probed.endpoints(), probed.checkedAt(), "panel");
    }
}
