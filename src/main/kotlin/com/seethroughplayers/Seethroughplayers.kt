package com.seethroughplayers

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.event.SimplePacketListenerAbstract
import com.github.retrooper.packetevents.event.simple.PacketPlaySendEvent
import com.github.retrooper.packetevents.protocol.packettype.PacketType
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnPlayer
import io.github.retrooper.packetevents.factory.spigot.SpigotPacketEventsBuilder
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import java.util.UUID
import java.util.HashSet
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor

class Seethroughplayers : JavaPlugin() {
    private val transparentPlayers = HashSet<UUID>()

    override fun onLoad() {
        PacketEvents.setAPI(SpigotPacketEventsBuilder.build(this))
        PacketEvents.getAPI().load()
    }

    override fun onEnable() {
        PacketEvents.getAPI().init()
        PacketEvents.getAPI().eventManager.registerListener(PacketListener())
        getCommand("seethrough")?.setExecutor(SeeThroughCommand())
        logger.info("SeeThroughPlayers enabled!")
    }

    override fun onDisable() {
        // Remove effects from all transparent players
        transparentPlayers.forEach { uuid ->
            Bukkit.getPlayer(uuid)?.let { player ->
                removeTransparency(player)
            }
        }
        PacketEvents.getAPI().terminate()
    }

    private fun makePlayerTransparent(player: Player) {
        if (transparentPlayers.add(player.uniqueId)) {
            // Apply invisibility effect
            player.addPotionEffect(PotionEffect(PotionEffectType.INVISIBILITY, Int.MAX_VALUE, 0, false, false))

            // Create team packets for all viewers
            Bukkit.getOnlinePlayers().forEach { viewer ->
                sendTeamPacket(viewer, player, true)
            }
        }
    }

    private fun removeTransparency(player: Player) {
        if (transparentPlayers.remove(player.uniqueId)) {
            // Remove invisibility effect
            player.removePotionEffect(PotionEffectType.INVISIBILITY)

            // Remove team packets for all viewers
            Bukkit.getOnlinePlayers().forEach { viewer ->
                sendTeamPacket(viewer, player, false)
            }
        }
    }

    private fun sendTeamPacket(viewer: Player, target: Player, create: Boolean) {
        try {
            // Generate unique team name for this viewer-target pair
            val teamName = "${viewer.uniqueId.mostSignificantBits}.${target.uniqueId.mostSignificantBits}"

            if (create) {
                // Create team info
                val teamInfo = WrapperPlayServerTeams.ScoreBoardTeamInfo(
                    Component.empty(), // displayName
                    Component.empty(), // prefix
                    Component.empty(), // suffix
                    WrapperPlayServerTeams.NameTagVisibility.ALWAYS,
                    WrapperPlayServerTeams.CollisionRule.ALWAYS,
                    NamedTextColor.GRAY, // Semi-transparent color
                    WrapperPlayServerTeams.OptionData.FRIENDLY_CAN_SEE_INVISIBLE // Allow seeing invisible teammates
                )

                // Create team packet
                val packet = WrapperPlayServerTeams(
                    teamName,
                    WrapperPlayServerTeams.TeamMode.CREATE,
                    teamInfo,
                    mutableListOf(viewer.name, target.name)
                )

                PacketEvents.getAPI().playerManager.sendPacket(viewer, packet)
            } else {
                // Remove team packet
                val packet = WrapperPlayServerTeams(
                    teamName,
                    WrapperPlayServerTeams.TeamMode.REMOVE,
                    null as WrapperPlayServerTeams.ScoreBoardTeamInfo?,
                    mutableListOf<String>()
                )

                PacketEvents.getAPI().playerManager.sendPacket(viewer, packet)
            }
        } catch (e: Exception) {
            logger.warning("Failed to send team packet: ${e.message}")
            e.printStackTrace()
        }
    }

    private inner class SeeThroughCommand : CommandExecutor {
        override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<String>): Boolean {
            if (sender !is Player) {
                sender.sendMessage("This command can only be used by players!")
                return true
            }

            val player = sender

            if (transparentPlayers.contains(player.uniqueId)) {
                removeTransparency(player)
                player.sendMessage("§cTransparency disabled!")
            } else {
                makePlayerTransparent(player)
                player.sendMessage("§aTransparency enabled!")
            }

            return true
        }
    }

    private inner class PacketListener : SimplePacketListenerAbstract() {
        override fun onPacketPlaySend(event: PacketPlaySendEvent) {
            if (event.packetType === PacketType.Play.Server.SPAWN_PLAYER) {
                try {
                    @Suppress("UNCHECKED_CAST")
                    val viewer = event.getPlayer<Any>() as? Player ?: return

                    // Use WrapperPlayServerSpawnPlayer to access the entity ID
                    val spawnPacket = WrapperPlayServerSpawnPlayer(event)
                    val entityId = spawnPacket.entityId

                    val targetPlayer = Bukkit.getOnlinePlayers().find { it.entityId == entityId } ?: return

                    if (transparentPlayers.contains(targetPlayer.uniqueId)) {
                        // Fix the runTask ambiguity by explicitly using a Runnable
                        Bukkit.getScheduler().runTask(this@Seethroughplayers, Runnable {
                            sendTeamPacket(viewer, targetPlayer, true)
                        })
                    }
                } catch (e: Exception) {
                    logger.warning("Error handling spawn packet: ${e.message}")
                }
            }
        }
    }
}