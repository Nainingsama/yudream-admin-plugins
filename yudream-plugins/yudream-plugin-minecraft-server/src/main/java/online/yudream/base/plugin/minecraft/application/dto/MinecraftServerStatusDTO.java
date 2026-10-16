package online.yudream.base.plugin.minecraft.application.dto;

import java.util.List;

public record MinecraftServerStatusDTO(
        String serverId,
        String status,
        int onlinePlayers,
        int maxPlayers,
        List<MinecraftEndpointStatusDTO> endpoints,
        long checkedAt,
        /** 在线判定依据：null＝线路探测；"panel"＝面板容器状态兜底。 */
        String source
) {
}
