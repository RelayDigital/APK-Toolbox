package io.github.bl3xand.apkcloner.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import io.github.bl3xand.apkcloner.BuildConfig
import io.github.bl3xand.apkcloner.R
import io.github.bl3xand.apkcloner.databinding.SheetSettingsBinding
import io.github.bl3xand.apkcloner.settings.AppSettings
import io.github.bl3xand.apkcloner.update.AutoUpdateWorker

class SettingsSheet : BottomSheetDialogFragment() {

    private var _binding: SheetSettingsBinding? = null
    private val binding get() = _binding!!

    // Auto-update works without it; the permission only matters for updates the system refuses
    // to install without a confirmation, which are then offered as a notification.
    // Scheduling waits for the answer: the first check starts right away, and a confirmation it
    // posts before the permission is granted would be lost.
    private val requestNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        context?.let(AutoUpdateWorker::schedule)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = SheetSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val settings = AppSettings(requireContext())
        val intervals = resources.getStringArray(R.array.check_intervals)

        fun render() {
            binding.sliderInterval.isEnabled = settings.autoUpdate
            binding.textInterval.isEnabled = settings.autoUpdate
            binding.textInterval.text = getString(R.string.settings_check_interval, intervals[settings.checkInterval])
        }

        binding.switchAutoUpdate.isChecked = settings.autoUpdate
        binding.sliderInterval.valueTo = (AppSettings.CHECK_INTERVAL_DAYS.size - 1).toFloat()
        binding.sliderInterval.value = settings.checkInterval.toFloat()
        binding.sliderInterval.setLabelFormatter { intervals[it.toInt()] }
        render()

        binding.rowAutoUpdate.setOnClickListener { binding.switchAutoUpdate.toggle() }
        binding.switchAutoUpdate.setOnCheckedChangeListener { _, checked ->
            settings.autoUpdate = checked
            if (checked) {
                requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                AutoUpdateWorker.schedule(requireContext())
            }
            render()
        }
        binding.sliderInterval.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            settings.checkInterval = value.toInt()
            AutoUpdateWorker.schedule(requireContext())
            render()
        }

        binding.buttonPlayProtect.setOnClickListener {
            // Not a public entry point: fall back to the general security settings if it is missing.
            runCatching { startActivity(Intent().setClassName("com.google.android.gms", PLAY_PROTECT_SETTINGS)) }
                .onFailure { startActivity(Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS)) }
        }

        binding.textVersion.text = getString(R.string.settings_version, BuildConfig.VERSION_NAME)
        binding.buttonSource.setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.source_url))))
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "settings"
        private const val PLAY_PROTECT_SETTINGS = "com.google.android.gms.security.settings.VerifyAppsSettingsActivity"
    }
}
