package hu.motor.telemetria.ui

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ViewGroup
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import hu.motor.telemetria.databinding.ItemWaypointBinding
import hu.motor.telemetria.net.Waypoint
import java.util.Collections

/**
 * A köztes pontok listája: húzással átrendezhető, a kereszttel törölhető.
 *
 * Az átrendezés után NEM tervezünk újra magától - a tervezés a gombra indul.
 * Kesztyűben könnyű véletlenül elmozdítani egy pontot, és minden mozdítás
 * több útvonaljelölt végigszámolását jelentené.
 */
class RouteWaypointAdapter(
    private val points: MutableList<Waypoint>,
    private val onChanged: () -> Unit,
    private val onStartDrag: (RecyclerView.ViewHolder) -> Unit
) : RecyclerView.Adapter<RouteWaypointAdapter.Holder>() {

    class Holder(val binding: ItemWaypointBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemWaypointBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = points.size

    @SuppressLint("ClickableViewAccessibility")
    override fun onBindViewHolder(holder: Holder, position: Int) {
        val point = points[position]
        holder.binding.tvIndex.text = (position + 1).toString()
        holder.binding.tvName.text = (point.name ?: String.format("%.5f, %.5f", point.lat, point.lon)) +
            if (position > 0 && position < points.lastIndex) {
                if (point.via) " · kötelező út" else " · megálló"
            } else ""

        holder.binding.btnRemove.setOnClickListener {
            val index = holder.bindingAdapterPosition
            if (index == RecyclerView.NO_POSITION) return@setOnClickListener
            points.removeAt(index)
            notifyItemRemoved(index)
            // A sorszámok a törlés alatt mind elcsúsznak, ezért újra kell rajzolni.
            notifyItemRangeChanged(index, points.size - index)
            onChanged()
        }

        holder.binding.ivDrag.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) onStartDrag(holder)
            false
        }
    }

    fun move(from: Int, to: Int) {
        Collections.swap(points, from, to)
        notifyItemMoved(from, to)
    }

    /** A húzás végén frissítjük a sorszámokat és szólunk a fragmentnek. */
    fun dragFinished() {
        notifyItemRangeChanged(0, points.size)
        onChanged()
    }

    companion object {
        fun touchHelper(adapter: RouteWaypointAdapter): ItemTouchHelper =
            ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
                ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
            ) {
                override fun onMove(
                    recyclerView: RecyclerView,
                    viewHolder: RecyclerView.ViewHolder,
                    target: RecyclerView.ViewHolder
                ): Boolean {
                    adapter.move(viewHolder.bindingAdapterPosition, target.bindingAdapterPosition)
                    return true
                }

                override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

                override fun isLongPressDragEnabled(): Boolean = false

                override fun clearView(
                    recyclerView: RecyclerView,
                    viewHolder: RecyclerView.ViewHolder
                ) {
                    super.clearView(recyclerView, viewHolder)
                    adapter.dragFinished()
                }
            })
    }
}
