package hu.motor.telemetria.ui

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import hu.motor.telemetria.R
import hu.motor.telemetria.TrackDetailActivity
import hu.motor.telemetria.analysis.AnalysisRepository
import hu.motor.telemetria.data.AppDatabase
import hu.motor.telemetria.data.MonthStats
import hu.motor.telemetria.data.Track
import hu.motor.telemetria.databinding.FragmentStatsBinding
import hu.motor.telemetria.databinding.ItemMonthBinding
import hu.motor.telemetria.databinding.ItemSectionBinding
import hu.motor.telemetria.SettingsActivity
import hu.motor.telemetria.util.Fmt
import hu.motor.telemetria.widget.StatsWidgetRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Összesített statisztika a mentett túrákból: összesítők, rekordok, havi bontás
 * és a túránkénti elemzés. Az adatok helyből jönnek, tehát net nélkül is
 * látszanak; a szerver és a téma beállításai a Beállítások képernyőn vannak.
 */
class StatsFragment : Fragment() {

    companion object {
        /** Ennyi legutóbbi túrát mutatunk az elemzés-listában. */
        private const val RECENT_TRACK_COUNT = 10
    }

    private var _binding: FragmentStatsBinding? = null
    private val binding get() = _binding!!

    private val dao by lazy { AppDatabase.get(requireContext()).trackDao() }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentStatsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnFetchAnalyses.setOnClickListener { fetchMissingAnalyses() }

        binding.toolbar.inflateMenu(R.menu.toolbar_settings)
        binding.toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_settings) {
                startActivity(Intent(requireContext(), SettingsActivity::class.java))
                true
            } else {
                false
            }
        }

        // A lista változásaira (új túra, törlés) magától frissül az összesítő.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                dao.observeFinishedTracks().collectLatest { loadStats() }
            }
        }
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) loadStats()
    }

    // --- statisztika -----------------------------------------------------------

    private fun loadStats() {
        viewLifecycleOwner.lifecycleScope.launch {
            val data = withContext(Dispatchers.IO) {
                StatsData(
                    totals = dao.getTotals(),
                    months = dao.getMonthlyStats(12),
                    longest = dao.getLongestTrack(),
                    fastest = dao.getFastestTrack()
                )
            }
            if (_binding == null) return@launch
            renderTotals(data)
            renderMonths(data.months)
            StatsWidgetRenderer.refresh(requireContext())
            loadTrackAnalyses()
        }
    }

    // --- túránkénti elemzés ----------------------------------------------------

    /** A már letöltött elemzések listája; ami hiányzik, azt a gomb hozza le. */
    private fun loadTrackAnalyses() {
        viewLifecycleOwner.lifecycleScope.launch {
            val tracks = withContext(Dispatchers.IO) { dao.getRecentTracks(RECENT_TRACK_COUNT) }
            val analyses = AnalysisRepository.cachedAll(requireContext(), tracks.map { it.id })
            if (_binding == null) return@launch

            val container = binding.analysisList
            container.removeAllViews()
            val inflater = LayoutInflater.from(requireContext())

            if (tracks.isEmpty()) {
                val empty = TextView(requireContext())
                empty.setText(R.string.stats_empty)
                empty.textSize = 13f
                empty.alpha = 0.6f
                container.addView(empty)
                binding.btnFetchAnalyses.visibility = View.GONE
                return@launch
            }

            binding.btnFetchAnalyses.visibility =
                if (tracks.any { analyses[it.id] == null }) View.VISIBLE else View.GONE

            for (track in tracks) {
                val row = ItemSectionBinding.inflate(inflater, container, false)
                val analysis = analyses[track.id]
                val summary = analysis?.summary

                row.tvSectionTitle.text = Fmt.dateTime(track.startTime)
                if (summary == null) {
                    row.tvSectionDetail.text = getString(R.string.analysis_row_missing)
                    row.tvSectionValue.text = Fmt.distance(track.distanceMeters)
                    row.marker.backgroundTintList =
                        ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.bar_track))
                } else {
                    row.tvSectionDetail.text = getString(
                        R.string.analysis_row_detail,
                        Fmt.distance(summary.distanceMeters),
                        Fmt.meters(summary.biggestClimb?.elevationDeltaMeters ?: 0.0),
                        summary.fastSectionCount
                    )
                    row.tvSectionValue.text = Fmt.kmh(summary.maxKmh)
                    val color = ContextCompat.getColor(requireContext(), R.color.brand)
                    row.marker.backgroundTintList = ColorStateList.valueOf(color)
                    row.tvSectionValue.setTextColor(color)
                }

                row.root.setOnClickListener {
                    startActivity(
                        Intent(requireContext(), TrackDetailActivity::class.java)
                            .putExtra(TrackDetailActivity.EXTRA_TRACK_ID, track.id)
                    )
                }
                container.addView(row.root)
            }
        }
    }

    /**
     * A hiányzó elemzések lekérése egyesével. A szerver az elsőnél számol
     * (Overpass hívás), utána már a tárolt eredményt adja vissza.
     */
    private fun fetchMissingAnalyses() {
        binding.btnFetchAnalyses.isEnabled = false
        binding.tvAnalysisProgress.visibility = View.VISIBLE

        viewLifecycleOwner.lifecycleScope.launch {
            val tracks = withContext(Dispatchers.IO) { dao.getRecentTracks(RECENT_TRACK_COUNT) }
            val cached = AnalysisRepository.cachedAll(requireContext(), tracks.map { it.id })
            val missing = tracks.filter { cached[it.id] == null }

            var done = 0
            for (track in missing) {
                if (_binding == null) return@launch
                binding.tvAnalysisProgress.text =
                    getString(R.string.analysis_fetching, done + 1, missing.size)
                AnalysisRepository.load(requireContext(), track).onSuccess { done++ }
            }

            if (_binding == null) return@launch
            binding.tvAnalysisProgress.text = getString(R.string.analysis_fetch_done, done)
            binding.btnFetchAnalyses.isEnabled = true
            loadTrackAnalyses()
        }
    }

    private class StatsData(
        val totals: hu.motor.telemetria.data.TotalStats,
        val months: List<MonthStats>,
        val longest: Track?,
        val fastest: Track?
    )

    private fun renderTotals(data: StatsData) {
        val totals = data.totals
        binding.tvTrackCount.text = totals.trackCount.toString()
        binding.tvTotalDistance.text = Fmt.distance(totals.distanceMeters)
        binding.tvTotalDuration.text = Fmt.longDuration(totals.durationMillis)
        binding.tvTotalMoving.text = Fmt.longDuration(totals.movingMillis)
        binding.tvTotalElevation.text = Fmt.meters(totals.maxElevationGainMeters)
        binding.tvTopSpeed.text = Fmt.speedKmhUnit(totals.maxSpeedMps)
        binding.tvAvgSpeed.text = Fmt.speedKmhUnit(
            if (totals.movingMillis > 0) {
                (totals.distanceMeters / (totals.movingMillis / 1000.0)).toFloat()
            } else {
                0f
            }
        )
        binding.tvAvgDistance.text = Fmt.distance(
            if (totals.trackCount > 0) totals.distanceMeters / totals.trackCount else 0.0
        )

        binding.tvEmptyStats.visibility = if (totals.trackCount == 0) View.VISIBLE else View.GONE

        binding.tvLongest.text = data.longest?.let {
            getString(
                R.string.record_line,
                Fmt.distance(it.distanceMeters),
                Fmt.dateTime(it.startTime)
            )
        } ?: getString(R.string.no_data)

        binding.tvFastest.text = data.fastest?.let {
            getString(
                R.string.record_line,
                Fmt.speedKmhUnit(it.maxSpeedMps),
                Fmt.dateTime(it.startTime)
            )
        } ?: getString(R.string.no_data)

        binding.tvFirstRide.text = if (totals.firstTrackAt > 0) {
            Fmt.dateTime(totals.firstTrackAt)
        } else {
            getString(R.string.no_data)
        }
    }

    /** Havi bontás egyszerű sávdiagrammal: a leghosszabb hónap a teljes szélesség. */
    private fun renderMonths(months: List<MonthStats>) {
        val container = binding.monthContainer
        container.removeAllViews()
        binding.tvMonthsEmpty.visibility = if (months.isEmpty()) View.VISIBLE else View.GONE

        val maxDistance = months.maxOfOrNull { it.distanceMeters } ?: 0.0
        val inflater = LayoutInflater.from(requireContext())

        for (month in months) {
            val row = ItemMonthBinding.inflate(inflater, container, false)
            row.tvMonth.text = Fmt.monthLabel(month.month)
            row.tvMonthDistance.text = Fmt.distance(month.distanceMeters)
            row.tvMonthDetail.text = getString(
                R.string.month_detail,
                month.trackCount,
                Fmt.longDuration(month.durationMillis)
            )

            val ratio = if (maxDistance > 0) (month.distanceMeters / maxDistance).toFloat() else 0f
            // A két súly aránya adja a sáv kitöltöttségét (a 0 is látszódjon egy csíkként).
            (row.barFill.layoutParams as LinearLayout.LayoutParams).weight = ratio.coerceAtLeast(0.02f)
            (row.barRest.layoutParams as LinearLayout.LayoutParams).weight = (1f - ratio).coerceAtLeast(0f)
            row.barFill.requestLayout()

            container.addView(row.root)
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
