package hu.motor.telemetria.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import hu.motor.telemetria.R
import hu.motor.telemetria.databinding.ItemRouteSectionBinding
import hu.motor.telemetria.net.RouteSection

/**
 * A terv szakaszai. Koppintásra a térkép rááll a szakaszra.
 *
 * A "forrás" sor szándékosan látszik: onnan derül ki, hogy a szakasz idejét a
 * saját mért tempód adta, vagy csak a geometriából becsültük. A 65 ezer pontod
 * alig 379 km egyedi utat fed le, ezért ez a különbség gyakran előjön, és
 * jobb kimondani, mint elrejteni.
 */
class RouteSectionAdapter(
    private val onClick: (RouteSection) -> Unit
) : RecyclerView.Adapter<RouteSectionAdapter.Holder>() {

    private var sections: List<RouteSection> = emptyList()

    class Holder(val binding: ItemRouteSectionBinding) : RecyclerView.ViewHolder(binding.root)

    fun submit(items: List<RouteSection>) {
        // A szerver már összevonta a rövid íveket, itt csak a maradék apró
        // darabokat szűrjük - de a hajtűt és a szűk kanyart mindig mutatjuk,
        // az akkor is fontos, ha csak 40 méter.
        sections = items.filter {
            it.distanceMeters >= 80 || it.type == "HAIRPIN" || it.type == "TIGHT"
        }
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemRouteSectionBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = sections.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val section = sections[position]
        val context = holder.itemView.context

        holder.binding.colourBar.setBackgroundColor(
            ContextCompat.getColor(context, CornerStyle.colourOf(section.type))
        )

        val name = section.road?.takeIf { it.isNotBlank() }
        holder.binding.tvSectionTitle.text = buildString {
            append(context.getString(CornerStyle.labelOf(section.type)))
            if (name != null) append(" · ").append(name)
            append(" · ").append(formatDistance(section.distanceMeters))
        }

        holder.binding.tvSectionDetail.text = buildString {
            if (section.type != "STRAIGHT") append("R=").append(section.minRadiusMeters).append(" m · ")
            section.limitKmh?.let { append("limit ").append(it).append(" km/h · ") }
            append(sourceText(context, section))
        }

        holder.binding.tvSectionSpeed.text = "${section.predictedKmh} km/h"
        holder.itemView.setOnClickListener { onClick(section) }
    }

    private fun sourceText(context: android.content.Context, section: RouteSection): String =
        when (section.source) {
            "CELL" -> context.getString(R.string.route_source_cell, section.samples)
            "BUCKET" -> context.getString(R.string.route_source_bucket, section.samples)
            "DEFAULT" -> context.getString(R.string.route_source_default)
            else -> context.getString(R.string.route_source_curve)
        }

    private fun formatDistance(meters: Int): String =
        if (meters >= 1000) String.format("%.1f km", meters / 1000.0) else "$meters m"
}

/** A kanyarosztályok színe és neve – a térkép és a lista ugyanazt használja. */
object CornerStyle {

    fun colourOf(type: String): Int = when (type) {
        "HAIRPIN" -> R.color.corner_hairpin
        "TIGHT" -> R.color.corner_tight
        "MEDIUM" -> R.color.corner_medium
        "GENTLE" -> R.color.corner_gentle
        else -> R.color.corner_straight
    }

    fun labelOf(type: String): Int = when (type) {
        "HAIRPIN" -> R.string.corner_hairpin_name
        "TIGHT" -> R.string.corner_tight_name
        "MEDIUM" -> R.string.corner_medium_name
        "GENTLE" -> R.string.corner_gentle_name
        else -> R.string.corner_straight_name
    }

    /** A szerver indexben küldi: 0 = hajtű … 4 = egyenes. */
    fun typeOf(index: Int): String = when (index) {
        0 -> "HAIRPIN"
        1 -> "TIGHT"
        2 -> "MEDIUM"
        3 -> "GENTLE"
        else -> "STRAIGHT"
    }
}
