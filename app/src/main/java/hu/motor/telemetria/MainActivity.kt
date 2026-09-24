package hu.motor.telemetria

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import hu.motor.telemetria.databinding.ActivityMainBinding
import hu.motor.telemetria.sync.SyncManager
import hu.motor.telemetria.ui.MapFragment
import hu.motor.telemetria.ui.RouteFragment
import hu.motor.telemetria.ui.StatsFragment
import hu.motor.telemetria.ui.TracksFragment

/**
 * Az alkalmazás váza: alul fülek, felül a kiválasztott fül tartalma.
 *
 * A fülek fragmentjeit nem cseréljük, csak elrejtjük – így a térkép nem tölti
 * újra a csempéket és nem veszti el a nyomvonalat, amikor visszalépünk rá.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        /** A widget "Indítás" gombja ezzel nyitja meg az appot. */
        const val EXTRA_START_TRACKING = "hu.motor.telemetria.extra.START_TRACKING"

        /** Melyik fül nyíljon meg (statisztika widget koppintása). */
        const val EXTRA_OPEN_TAB = "hu.motor.telemetria.extra.OPEN_TAB"
        const val TAB_STATS = "stats"

        private const val TAG_MAP = "tab_map"
        private const val TAG_TRACKS = "tab_tracks"
        private const val TAG_STATS = "tab_stats"
        private const val TAG_ROUTE = "tab_route"
    }

    private lateinit var binding: ActivityMainBinding

    /**
     * A widget indítási kérése. A térkép fül veszi át, amint látható – így az
     * engedélykérés és a GPS-ellenőrzés ugyanott fut, ahol kézi indításkor.
     */
    private var pendingStartTracking = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.tab_map -> showTab(TAG_MAP)
                R.id.tab_tracks -> showTab(TAG_TRACKS)
                R.id.tab_route -> showTab(TAG_ROUTE)
                R.id.tab_stats -> showTab(TAG_STATS)
            }
            true
        }
        // A fül ismételt megnyomása ne építse újra a fragmentet.
        binding.bottomNav.setOnItemReselectedListener { }

        if (savedInstanceState == null) {
            // A fület kézzel is felépítjük, nem csak a kijelölésre bízzuk:
            // így akkor sem marad üres a képernyő, ha a kijelölés nem vált eseményt.
            binding.bottomNav.selectedItemId = R.id.tab_map
            showTab(TAG_MAP)
        }

        handleWidgetIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleWidgetIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        // Előtérbe kerüléskor jó alkalom pótolni, ami offline maradt.
        SyncManager.requestSync()
    }

    private fun handleWidgetIntent(intent: Intent?) {
        if (intent == null) return

        if (intent.getStringExtra(EXTRA_OPEN_TAB) == TAB_STATS) {
            intent.removeExtra(EXTRA_OPEN_TAB)
            binding.bottomNav.selectedItemId = R.id.tab_stats
            showTab(TAG_STATS)
        }

        if (intent.getBooleanExtra(EXTRA_START_TRACKING, false)) {
            intent.removeExtra(EXTRA_START_TRACKING)
            pendingStartTracking = true
            binding.bottomNav.selectedItemId = R.id.tab_map
            showTab(TAG_MAP)
        }
    }

    /** A térkép fül kérdezi meg, kell-e mérést indítania a widget miatt. */
    fun consumePendingStart(): Boolean {
        val pending = pendingStartTracking
        pendingStartTracking = false
        return pending
    }

    private fun showTab(tag: String) {
        val manager = supportFragmentManager
        val transaction = manager.beginTransaction()

        manager.fragments.forEach { transaction.hide(it) }

        val existing = manager.findFragmentByTag(tag)
        if (existing == null) {
            transaction.add(R.id.tabContent, createFragment(tag), tag)
        } else {
            transaction.show(existing)
        }
        transaction.commit()
    }

    private fun createFragment(tag: String): Fragment = when (tag) {
        TAG_TRACKS -> TracksFragment()
        TAG_STATS -> StatsFragment()
        TAG_ROUTE -> RouteFragment()
        else -> MapFragment()
    }
}
