package io.github.st0n89.bathyapp.core.seismic

/**
 * Bottom-type classification and geoacoustic layer properties.
 *
 * Classification (per profile node) from GlobSed v3 total sediment
 * thickness [m] and GEBCO water depth [m]:
 *   * ROCK  — sediment <= [THIN_M] m: effectively exposed basement /
 *     hard bottom (includes ocean floor with zero mapped sediment).
 *   * MUD   — deeper than [SHELF_M] and sediment > THIN: pelitic
 *     deep-sea sediment (Hamilton 1980 clay/silt class).
 *   * SAND  — shallower than [SHELF_M] and sediment > THIN: shelf sand
 *     (Hamilton 1980 sand class).
 *
 * Properties are surface-layer values of Hamilton (1980)-style
 * geoacoustic tables; the "ROCK" properties also serve as the basement
 * half-space below the sediment layer.
 */
enum class BottomClass { MUD, SAND, ROCK }

object BottomClasses {
    /** Sediment at or below this thickness counts as hard bottom. */
    const val THIN_M = 25.0

    /** Water depth at or below this is treated as shelf. */
    const val SHELF_M = 200.0

    data class Props(val vp: Double, val vs: Double, val rho: Double, val qp: Double, val qs: Double)

    val WATER = Props(vp = 1500.0, vs = 0.0, rho = 1000.0, qp = 1000.0, qs = 0.0)
    val MUD = Props(vp = 1550.0, vs = 60.0, rho = 1550.0, qp = 80.0, qs = 40.0)
    val SAND = Props(vp = 1800.0, vs = 140.0, rho = 1950.0, qp = 40.0, qs = 25.0)
    val ROCK = Props(vp = 4500.0, vs = 2400.0, rho = 2600.0, qp = 100.0, qs = 60.0)

    /** Basement half-space below the sediment layer (rock properties). */
    val BASEMENT = ROCK

    fun classify(waterDepthM: Double, sedThicknessM: Double): BottomClass = when {
        sedThicknessM <= THIN_M -> BottomClass.ROCK
        waterDepthM <= SHELF_M -> BottomClass.SAND
        else -> BottomClass.MUD
    }
}
