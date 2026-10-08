package io.github.bl3xand.apkcloner.ui

import android.Manifest
import android.content.Intent
import android.content.res.ColorStateList
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.bl3xand.apkcloner.BuildConfig
import io.github.bl3xand.apkcloner.R
import io.github.bl3xand.apkcloner.clone.SigningKeys
import io.github.bl3xand.apkcloner.databinding.DialogKeyPasswordBinding
import io.github.bl3xand.apkcloner.databinding.SheetSettingsBinding
import io.github.bl3xand.apkcloner.install.PlayProtect
import io.github.bl3xand.apkcloner.settings.AppSettings
import io.github.bl3xand.apkcloner.settings.AppSettings.InstallMethod
import io.github.bl3xand.apkcloner.shizuku.ShizukuBridge
import io.github.bl3xand.apkcloner.update.AutoUpdateWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

class SettingsSheet : BottomSheetDialogFragment() {

    private var _binding: SheetSettingsBinding? = null
    private val binding get() = _binding!!
    private lateinit var settings: AppSettings

    // Scheduling waits for the answer: the first check starts right away, and a notification it
    // posts before the permission is granted would be lost.
    private val requestNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        context?.let(AutoUpdateWorker::schedule)
    }

    // Set once the user has answered Shizuku's prompt with "deny", to say so instead of just asking again.
    private var shizukuDenied = false

    private val keys by lazy { SigningKeys.get(requireContext()) }

    private val importKeyFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) askPassword(R.string.key_import, withAlias = true) { password, alias ->
            keyAction(R.string.key_imported) {
                requireContext().contentResolver.openInputStream(uri)!!.use { keys.import(it, password, alias) }
            }
        }
    }

    // Held between asking for the password and the user picking where to write the file.
    private var exportPassword: CharArray? = null
    private val exportKeyFile = registerForActivityResult(ActivityResultContracts.CreateDocument("application/x-pkcs12")) { uri ->
        val password = exportPassword
        exportPassword = null
        if (uri != null && password != null) keyAction(R.string.key_exported) {
            requireContext().contentResolver.openOutputStream(uri, "wt")!!.use { keys.export(it, password) }
        }
    }

    private val shizukuPermissionListener = Shizuku.OnRequestPermissionResultListener { _, result ->
        shizukuDenied = result != PackageManager.PERMISSION_GRANTED
        render()
    }
    private val shizukuBinderListener = Shizuku.OnBinderReceivedListener { render() }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = SheetSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        settings = AppSettings(requireContext())
        val intervals = resources.getStringArray(R.array.check_intervals)

        binding.switchCheckUpdates.isChecked = settings.checkUpdates
        binding.switchAutoInstall.isChecked = settings.autoInstall
        binding.sliderInterval.valueTo = (AppSettings.CHECK_INTERVAL_DAYS.size - 1).toFloat()
        binding.sliderInterval.value = settings.checkInterval.toFloat()
        binding.sliderInterval.setLabelFormatter { intervals[it.toInt()] }
        binding.toggleMethod.check(
            if (settings.installMethod == InstallMethod.SHIZUKU) R.id.buttonMethodShizuku else R.id.buttonMethodStock
        )

        binding.rowCheckUpdates.setOnClickListener { binding.switchCheckUpdates.toggle() }
        binding.switchCheckUpdates.setOnCheckedChangeListener { _, checked ->
            settings.checkUpdates = checked
            if (checked) {
                requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                AutoUpdateWorker.schedule(requireContext())
            }
            render()
        }
        binding.buttonCheckNow.setOnClickListener {
            AutoUpdateWorker.runNow(requireContext())
            Toast.makeText(requireContext(), R.string.check_now_started, Toast.LENGTH_SHORT).show()
        }
        binding.rowAutoInstall.setOnClickListener { binding.switchAutoInstall.toggle() }
        binding.switchAutoInstall.setOnCheckedChangeListener { _, checked ->
            settings.autoInstall = checked
            render()
        }
        binding.sliderInterval.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            settings.checkInterval = value.toInt()
            AutoUpdateWorker.schedule(requireContext())
            render()
        }
        binding.toggleMethod.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            settings.installMethod = if (checkedId == R.id.buttonMethodShizuku) InstallMethod.SHIZUKU else InstallMethod.STOCK
            render()
        }

        binding.buttonKeyCreate.setOnClickListener {
            confirmKeyChange { keyAction(R.string.key_created) { keys.generate(KEY_COMMON_NAME) } }
        }
        binding.buttonKeyImport.setOnClickListener { confirmKeyChange { importKeyFile.launch(arrayOf("*/*")) } }
        binding.buttonKeyExport.setOnClickListener {
            askPassword(R.string.key_export, withAlias = false) { password, _ ->
                exportPassword = password
                exportKeyFile.launch(KEY_EXPORT_NAME)
            }
        }
        binding.buttonKeyReset.setOnClickListener { confirmKeyChange { keyAction(R.string.key_reset_done) { keys.reset() } } }

        binding.textVersion.text = getString(R.string.settings_version, BuildConfig.VERSION_NAME)
        binding.buttonSource.setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.source_url))))
        }

        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
        Shizuku.addBinderReceivedListenerSticky(shizukuBinderListener)
    }

    override fun onStart() {
        super.onStart()
        // Tall enough that a half-open sheet would cut the content off mid-section.
        (dialog as? BottomSheetDialog)?.behavior?.apply {
            skipCollapsed = true
            state = BottomSheetBehavior.STATE_EXPANDED
        }
    }

    override fun onResume() {
        super.onResume()
        // Shizuku may have been started or stopped while we were away.
        render()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        Shizuku.removeBinderReceivedListener(shizukuBinderListener)
        _binding = null
    }

    private fun render() {
        val binding = _binding ?: return
        renderKey()
        val intervals = resources.getStringArray(R.array.check_intervals)
        val checking = settings.checkUpdates
        binding.sliderInterval.isEnabled = checking
        binding.textInterval.isEnabled = checking
        binding.rowAutoInstall.isEnabled = checking
        binding.textAutoInstall.isEnabled = checking
        binding.switchAutoInstall.isEnabled = checking
        // The method only matters once something is installed without the user.
        binding.groupMethod.isVisible = checking && settings.autoInstall
        binding.textInterval.text = getString(R.string.settings_check_interval, intervals[settings.checkInterval])

        if (settings.installMethod == InstallMethod.SHIZUKU) {
            when (ShizukuBridge.state()) {
                ShizukuBridge.State.READY ->
                    showStatus(ok = true, R.string.shizuku_granted, R.string.shizuku_granted_description)
                ShizukuBridge.State.NOT_RUNNING ->
                    showStatus(ok = false, R.string.shizuku_not_running_title, R.string.shizuku_not_running)
                ShizukuBridge.State.NO_PERMISSION -> showStatus(
                    ok = false,
                    title = if (shizukuDenied) R.string.shizuku_denied else R.string.shizuku_title,
                    description = R.string.shizuku_description,
                    action = R.string.button_grant_shizuku,
                ) { ShizukuBridge.requestPermission(SHIZUKU_REQUEST_CODE) }
            }
        } else {
            when (PlayProtect.isEnabled(requireContext())) {
                false -> showStatus(ok = true, R.string.play_protect_off_title, R.string.play_protect_off_description)
                true -> showStatus(
                    ok = false, R.string.play_protect_on_title, R.string.play_protect_on_description,
                    R.string.settings_play_protect_button, ::openPlayProtect,
                )
                null -> showStatus(
                    ok = false, R.string.play_protect_unknown_title, R.string.play_protect_on_description,
                    R.string.settings_play_protect_button, ::openPlayProtect,
                )
            }
        }
    }

    private fun renderKey() {
        val binding = _binding ?: return
        val custom = keys.custom
        binding.rowKeyCustom.isVisible = custom != null
        if (custom == null) {
            binding.textKeyTitle.setText(R.string.key_bundled_title)
            binding.textKeyDescription.setText(R.string.key_bundled_description)
        } else {
            binding.textKeyTitle.setText(R.string.key_custom_title)
            binding.textKeyDescription.text = getString(
                R.string.key_custom_description,
                custom.certificate.subjectX500Principal.name,
                custom.fingerprint.take(FINGERPRINT_PREVIEW),
            )
        }
    }

    /** Replacing a key of the user's own strands whatever was signed with it, so ask first. */
    private fun confirmKeyChange(action: () -> Unit) {
        if (keys.custom == null) return action()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.key_replace_title)
            .setMessage(R.string.key_replace_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.key_replace_confirm) { _, _ -> action() }
            .show()
    }

    private fun askPassword(title: Int, withAlias: Boolean, onEntered: (CharArray, String) -> Unit) {
        val dialog = DialogKeyPasswordBinding.inflate(layoutInflater)
        dialog.layoutAlias.isVisible = withAlias
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setView(dialog.root)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                onEntered(dialog.editPassword.text.toString().toCharArray(), dialog.editAlias.text.toString().trim())
            }
            .show()
    }

    /** Key operations touch the keystore and the disk; keep them off the main thread. */
    private fun keyAction(success: Int, action: () -> Unit) {
        val context = requireContext().applicationContext
        lifecycleScope.launch {
            val error = withContext(Dispatchers.IO) { runCatching(action).exceptionOrNull() }
            val message = if (error == null) getString(success)
            else getString(R.string.key_failed, error.message ?: error.javaClass.simpleName)
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            renderKey()
        }
    }

    /**
     * The one card under the method switch. [ok] tints it as "nothing left to do"; otherwise it
     * explains what is in the way and, with [action], offers the way out.
     */
    private fun showStatus(
        ok: Boolean,
        @StringRes title: Int,
        @StringRes description: Int,
        @StringRes action: Int? = null,
        onAction: (() -> Unit)? = null,
    ) {
        val binding = _binding ?: return
        val accent = MaterialColors.getColor(
            binding.root,
            if (ok) androidx.appcompat.R.attr.colorPrimary else com.google.android.material.R.attr.colorOnSurfaceVariant,
        )
        binding.cardStatus.strokeColor =
            if (ok) accent else MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOutlineVariant)
        binding.imageStatus.setImageResource(if (ok) R.drawable.ic_check_circle else R.drawable.ic_info)
        binding.imageStatus.imageTintList = ColorStateList.valueOf(accent)
        binding.textStatusTitle.setText(title)
        binding.textStatusDescription.setText(description)
        binding.buttonStatusAction.isVisible = action != null
        if (action != null) binding.buttonStatusAction.setText(action)
        binding.buttonStatusAction.setOnClickListener { onAction?.invoke() }
    }

    private fun openPlayProtect() {
        // Not a public entry point: fall back to the general security settings if it is missing.
        runCatching { startActivity(Intent().setClassName("com.google.android.gms", PLAY_PROTECT_SETTINGS)) }
            .onFailure { startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS)) }
    }

    companion object {
        const val TAG = "settings"
        private const val SHIZUKU_REQUEST_CODE = 1001
        private const val KEY_COMMON_NAME = "APK Toolbox user"
        private const val KEY_EXPORT_NAME = "apk-toolbox-key.p12"
        private const val FINGERPRINT_PREVIEW = 23
        private const val PLAY_PROTECT_SETTINGS = "com.google.android.gms.security.settings.VerifyAppsSettingsActivity"
    }
}
