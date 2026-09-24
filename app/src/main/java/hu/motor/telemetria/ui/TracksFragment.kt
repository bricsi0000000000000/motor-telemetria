package hu.motor.telemetria.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import hu.motor.telemetria.R
import hu.motor.telemetria.SettingsActivity
import hu.motor.telemetria.TrackDetailActivity
import hu.motor.telemetria.data.AppDatabase
import hu.motor.telemetria.data.PendingDelete
import hu.motor.telemetria.data.Place
import hu.motor.telemetria.data.PlaceType
import hu.motor.telemetria.data.Track
import hu.motor.telemetria.data.TrackEndpoints
import hu.motor.telemetria.databinding.FragmentTracksBinding
import hu.motor.telemetria.databinding.ItemDayHeaderBinding
import hu.motor.telemetria.databinding.ItemTrackBinding
import hu.motor.telemetria.net.GeocodeRepository
import hu.motor.telemetria.sync.SyncManager
import hu.motor.telemetria.util.Fmt
import hu.motor.telemetria.widget.StatsWidgetRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

/**
 * A mentett túrák naplója napokra bontva. Egy koppintás megnyitja a túrát,
 * hosszú nyomás törli.
 */
class TracksFragment : Fragment() {

    companion object {
        /** Egy körben ennyi új címet oldunk fel (a többi a következő megnyitásra marad). */
        private const val MAX_LABELS_PER_PASS = 40

        /** Ennyi új cím után rajzoljuk újra a listát. */
        private const val LABEL_BATCH = 5
    }

    private var _binding: FragmentTracksBinding? = null
    private val binding get() = _binding!!

    private val dao by lazy { AppDatabase.get(requireContext()).trackDao() }
    private val placeDao by lazy { AppDatabase.get(requireContext()).placeDao() }

    /** A címek feloldása háttérben fut, listaváltáskor újraindul. */
    private var labelJob: Job? = null

    private val adapter = TrackAdapter(
        onClick = { track ->
            startActivity(
                Intent(requireContext(), TrackDetailActivity::class.java)
                    .putExtra(TrackDetailActivity.EXTRA_TRACK_ID, track.id)
            )
        },
        onLongClick = { track -> confirmDelete(track) }
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTracksBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.toolbar.inflateMenu(R.menu.toolbar_settings)
        binding.toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_settings) {
                startActivity(Intent(requireContext(), SettingsActivity::class.java))
                true
            } else {
                false
            }
        }

        binding.recycler.layoutManager = LinearLayoutManager(requireContext())
        binding.recycler.adapter = adapter

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                dao.observeFinishedTracks().collectLatest { tracks -> render(tracks) }
            }
        }
    }

    /** A csoportosításhoz a túrák végpontjai és a helyek is kellenek. */
    private suspend fun render(tracks: List<Track>) {
        val data = withContext(Dispatchers.IO) {
            val endpoints = dao.getTrackEndpoints().associateBy { it.trackId }
            val places = placeDao.getAll()
            // A már ismert címek azonnal látszanak, a többit utána oldjuk fel.
            val known = GeocodeRepository.cached(requireContext(), endpointKeys(endpoints, places))
            Triple(endpoints, places, known)
        }
        if (_binding == null) return

        val (endpoints, places, known) = data
        adapter.submitList(TrackGrouping.build(tracks, endpoints, places, known))
        binding.tvEmpty.visibility = if (tracks.isEmpty()) View.VISIBLE else View.GONE
        binding.tvCount.text = resources.getQuantityString(
            R.plurals.track_count, tracks.size, tracks.size
        )

        resolveMissingLabels(tracks, endpoints, places, known)
    }

    /** Azok a végpontok, amikhez nem tartozik megadott hely – ezeket kell betájolni. */
    private fun endpointKeys(
        endpoints: Map<Long, TrackEndpoints>,
        places: List<Place>
    ): List<String> = endpoints.values.flatMap { ends ->
        listOfNotNull(
            coordinateKey(ends.startLat, ends.startLon, places),
            coordinateKey(ends.endLat, ends.endLon, places)
        )
    }

    private fun coordinateKey(lat: Double?, lon: Double?, places: List<Place>): String? {
        if (lat == null || lon == null) return null
        // Ha mentett helyhez tartozik (a naplóbeli tűréssel), nem kell cím.
        if (TrackGrouping.placeFor(places, lat, lon) != null) return null
        return GeocodeRepository.key(lat, lon)
    }

    /**
     * A hiányzó címek feloldása egyesével, a legfrissebb túráktól kezdve. A
     * lista közben már használható; ahogy megvannak a címek, újrarajzoljuk.
     */
    private fun resolveMissingLabels(
        tracks: List<Track>,
        endpoints: Map<Long, TrackEndpoints>,
        places: List<Place>,
        known: Map<String, String>
    ) {
        labelJob?.cancel()
        labelJob = viewLifecycleOwner.lifecycleScope.launch {
            val labels = known.toMutableMap()
            var resolved = 0

            for (track in tracks) {
                val ends = endpoints[track.id] ?: continue
                val targets = listOfNotNull(
                    ends.startLat?.let { lat -> ends.startLon?.let { lon -> lat to lon } },
                    ends.endLat?.let { lat -> ends.endLon?.let { lon -> lat to lon } }
                )

                for ((lat, lon) in targets) {
                    val key = coordinateKey(lat, lon, places) ?: continue
                    if (labels.containsKey(key)) continue

                    val label = GeocodeRepository.resolve(requireContext(), lat, lon) ?: continue
                    labels[key] = label
                    resolved++

                    // Kis adagokban frissítünk, hogy ne villogjon a lista.
                    if (resolved % LABEL_BATCH == 0 && _binding != null) {
                        adapter.submitList(TrackGrouping.build(tracks, endpoints, places, labels))
                    }
                }
                if (resolved >= MAX_LABELS_PER_PASS) break
            }

            if (resolved > 0 && _binding != null) {
                adapter.submitList(TrackGrouping.build(tracks, endpoints, places, labels))
            }
        }
    }

    private fun confirmDelete(track: Track) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.delete_confirm_title)
            .setMessage(getString(R.string.delete_confirm_message, Fmt.dateTime(track.startTime)))
            .setPositiveButton(R.string.action_delete) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        // A szerverről is tűnjön el – ha most nincs net, a jelzés
                        // megvár minket a következő szinkronig.
                        dao.addPendingDelete(PendingDelete(track.id))
                        dao.deleteTrack(track.id)
                    }
                    SyncManager.requestSync()
                    StatsWidgetRenderer.refresh(requireContext())
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    override fun onDestroyView() {
        binding.recycler.adapter = null
        _binding = null
        super.onDestroyView()
    }
}

private const val TYPE_HEADER = 0
private const val TYPE_TRACK = 1

private class TrackAdapter(
    private val onClick: (Track) -> Unit,
    private val onLongClick: (Track) -> Unit
) : ListAdapter<TrackListItem, RecyclerView.ViewHolder>(DIFF) {

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<TrackListItem>() {
            override fun areItemsTheSame(oldItem: TrackListItem, newItem: TrackListItem): Boolean =
                when {
                    oldItem is TrackListItem.DayHeader && newItem is TrackListItem.DayHeader ->
                        oldItem.dayKey == newItem.dayKey

                    oldItem is TrackListItem.Entry && newItem is TrackListItem.Entry ->
                        oldItem.track.id == newItem.track.id

                    else -> false
                }

            override fun areContentsTheSame(oldItem: TrackListItem, newItem: TrackListItem) =
                oldItem == newItem
        }
    }

    class HeaderHolder(val binding: ItemDayHeaderBinding) : RecyclerView.ViewHolder(binding.root)

    class TrackHolder(val binding: ItemTrackBinding) : RecyclerView.ViewHolder(binding.root)

    override fun getItemViewType(position: Int): Int =
        if (getItem(position) is TrackListItem.DayHeader) TYPE_HEADER else TYPE_TRACK

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            HeaderHolder(ItemDayHeaderBinding.inflate(inflater, parent, false))
        } else {
            TrackHolder(ItemTrackBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is TrackListItem.DayHeader -> bindHeader(holder as HeaderHolder, item)
            is TrackListItem.Entry -> bindTrack(holder as TrackHolder, item)
        }
    }

    private fun bindHeader(holder: HeaderHolder, item: TrackListItem.DayHeader) {
        val context = holder.itemView.context
        holder.binding.tvDayLabel.text = dayLabel(context, item.startTime)
        holder.binding.tvDayDistance.text = Fmt.distance(item.distanceMeters)
        holder.binding.tvDaySummary.text = context.getString(
            R.string.day_summary,
            item.trackCount,
            Fmt.longDuration(item.movingMillis),
            Fmt.speedKmhUnit(item.avgSpeedMps),
            Fmt.speedKmhUnit(item.maxSpeedMps),
            Fmt.meters(item.elevationGainMeters)
        )
    }

    /** A mai és a tegnapi nap nevet kap, a többi dátumot. */
    private fun dayLabel(context: android.content.Context, timestamp: Long): String {
        val today = Calendar.getInstance()
        val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
        val key = Fmt.dayKey(timestamp)
        return when (key) {
            Fmt.dayKey(today.timeInMillis) -> context.getString(R.string.day_today)
            Fmt.dayKey(yesterday.timeInMillis) -> context.getString(R.string.day_yesterday)
            else -> Fmt.dayLabel(timestamp).replaceFirstChar { it.uppercase() }
        }
    }

    private fun bindTrack(holder: TrackHolder, item: TrackListItem.Entry) {
        val track = item.track
        val context = holder.itemView.context
        with(holder.binding) {
            // A napi fejléc alatt elég az indulás órája.
            tvDate.text = Fmt.timeOnly(track.startTime)
            tvDistance.text = Fmt.distance(track.distanceMeters)
            tvSummary.text = context.getString(
                R.string.track_summary,
                Fmt.duration(track.durationMillis),
                Fmt.speedKmhUnit(track.avgSpeedMps),
                Fmt.speedKmhUnit(track.maxSpeedMps)
            )

            bindRoute(this, item)

            // Egy pillantásra látszik, hogy felment-e már a szerverre.
            val pending = track.dirty || track.syncedPoints < track.pointCount
            tvSync.setText(if (pending) R.string.sync_pending_short else R.string.sync_done_short)
            tvSync.alpha = if (pending) 1f else 0.5f

            root.setOnClickListener { onClick(track) }
            root.setOnLongClickListener {
                onLongClick(track)
                true
            }
        }
    }

    /** "Otthon → Munkahely · oda", ha tudjuk, honnan hova ment. */
    private fun bindRoute(binding: ItemTrackBinding, item: TrackListItem.Entry) {
        val context = binding.root.context
        val fromName = item.from?.name ?: item.fromLabel
        val toName = item.to?.name ?: item.toLabel
        if (fromName == null && toName == null) {
            binding.tvRoute.visibility = View.GONE
            return
        }

        val unknown = context.getString(R.string.route_unknown)
        val route = context.getString(
            R.string.route_line,
            fromName ?: unknown,
            toName ?: unknown
        )

        val badge = when (item.pair) {
            PairKind.OUT -> context.getString(R.string.route_pair_out)
            PairKind.BACK -> context.getString(R.string.route_pair_back)
            PairKind.LOOP -> context.getString(R.string.route_loop)
            PairKind.NONE -> null
        }

        binding.tvRoute.visibility = View.VISIBLE
        binding.tvRoute.text =
            if (badge == null) route else context.getString(R.string.route_badge, route, badge)

        // Az oda-vissza párokat a hely típusának színe emeli ki.
        val colour = when {
            item.pair == PairKind.NONE -> R.color.on_surface
            item.to?.placeType == PlaceType.WORK || item.from?.placeType == PlaceType.WORK ->
                R.color.place_work

            item.to?.placeType == PlaceType.HOME || item.from?.placeType == PlaceType.HOME ->
                R.color.place_home

            else -> R.color.place_destination
        }
        binding.tvRoute.setTextColor(ContextCompat.getColor(context, colour))
        binding.tvRoute.alpha = if (item.pair == PairKind.NONE) 0.75f else 1f
    }
}
