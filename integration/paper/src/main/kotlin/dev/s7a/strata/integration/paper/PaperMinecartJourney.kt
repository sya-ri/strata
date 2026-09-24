package dev.s7a.strata.integration.paper

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.data.Rail
import org.bukkit.entity.Player
import org.bukkit.entity.minecart.RideableMinecart
import org.bukkit.util.Vector

/**
 * Moves one test player along a short rolling rail bed without teleporting or replacing its native container.
 * All calls run on the player's Folia region; track writes skip chunks not owned by that region.
 * The runner owns a disposable world, while this fixture removes its vehicle on every completed transaction.
 */
internal class PaperMinecartJourney(
    private val player: Player,
) : AutoCloseable {
    private val start = player.location
    private val world = player.world
    private val railY = start.blockY
    private val railZ = start.blockZ
    private val cart = createCart()

    /**
     * Distance observed by the server while the original player remains mounted.
     */
    var travelled: Double = 0.0
        private set

    /**
     * Advances the vehicle on ordinary server ticks and returns true after crossing four chunk widths.
     */
    fun tick(): Boolean {
        check(player.vehicle === cart) { "The acceptance player left its minecart." }
        travelled = cart.location.x - start.x
        val finished = 64.0 <= travelled
        if (finished.not()) layTrack(cart.location.blockX)
        cart.velocity = Vector(if (finished) 0.0 else 0.8, 0.0, 0.0)
        return finished
    }

    override fun close() {
        if (cart.isValid) cart.remove()
    }

    private fun createCart(): RideableMinecart {
        layTrack(start.blockX)
        val location = Location(world, start.blockX + 0.5, railY + 0.125, railZ + 0.5)
        return world.spawn(location, RideableMinecart::class.java).also { vehicle ->
            vehicle.maxSpeed = 0.8
            check(vehicle.addPassenger(player)) { "Could not mount the acceptance minecart." }
        }
    }

    private fun layTrack(center: Int) {
        for (x in center - 2..center + 16) {
            if (Bukkit.isOwnedByCurrentRegion(world, x shr 4, railZ shr 4).not()) continue
            world.getBlockAt(x, railY - 1, railZ).setType(Material.STONE, false)
            world.getBlockAt(x, railY + 1, railZ).setType(Material.AIR, false)
            world.getBlockAt(x, railY + 2, railZ).setType(Material.AIR, false)
            val rail = Material.RAIL.createBlockData() as Rail
            rail.shape = Rail.Shape.EAST_WEST
            world.getBlockAt(x, railY, railZ).setBlockData(rail, false)
        }
    }
}
