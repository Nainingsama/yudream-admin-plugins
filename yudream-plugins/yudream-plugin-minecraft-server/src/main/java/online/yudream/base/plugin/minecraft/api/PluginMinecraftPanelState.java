package online.yudream.base.plugin.minecraft.api;

/**
 * mcpanel 面板回传的实例运行状态。
 *
 * <p>{@code state} 为面板实例状态（running / exited / stopped / created / installing / unknown / deleted）；
 * {@code updatedAt} 为面板发送时刻（epoch ms）。面板按实例状态变化推送，重启后重新上报。</p>
 *
 * <p>{@code maxPlayers} 为面板从实例 {@code server.properties} 读到的 {@code max-players}
 * （读不到置 0）：ping 不可达时（同机部署的 NAT 回环等）面板侧仍能给出人数上限，
 * 供在线人数按 {@code 在线/上限} 展示。</p>
 */
public record PluginMinecraftPanelState(String instanceId, String state, long updatedAt, int maxPlayers) {
}
