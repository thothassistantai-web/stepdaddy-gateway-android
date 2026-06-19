package com.thothassistant.stepdaddy.gateway.ui

import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButtonToggleGroup
import com.thothassistant.stepdaddy.gateway.GatewayEnvironment
import com.thothassistant.stepdaddy.gateway.R
import com.thothassistant.stepdaddy.gateway.databinding.DialogQrCodeBinding

class QrCodeDialogController(
    private val activity: AppCompatActivity,
    private val environment: GatewayEnvironment,
) {
    private enum class AccessMode { LOCAL, REMOTE }

    private enum class UrlKind { PLAYLIST, EPG }

    fun show() {
        val binding = DialogQrCodeBinding.inflate(LayoutInflater.from(activity))
        var accessMode = AccessMode.LOCAL
        var urlKind = UrlKind.PLAYLIST
        var howItWorksExpanded = false

        binding.editRemoteBaseUrl.setText(environment.remoteGatewayUrl)
        binding.textPortForwardWarning.text = activity.getString(
            R.string.qr_port_forward_warning,
            environment.port,
        )
        binding.textHowItWorksStep2.text = activity.getString(
            R.string.qr_how_it_works_step_2,
            environment.port,
        )

        fun persistRemoteUrl() {
            environment.remoteGatewayUrl = binding.editRemoteBaseUrl.text?.toString().orEmpty()
        }

        fun resolveBaseUrl(): String? = when (accessMode) {
            AccessMode.LOCAL -> {
                val ip = LocalNetworkHelper.lanIpv4()
                if (ip == null) null else "http://$ip:${environment.port}"
            }
            AccessMode.REMOTE -> {
                val raw = binding.editRemoteBaseUrl.text?.toString()?.trim().orEmpty()
                raw.takeIf { it.isNotEmpty() }
            }
        }

        fun pathFor(kind: UrlKind): String = when (kind) {
            UrlKind.PLAYLIST -> "/tivimate-playlist.m3u8"
            UrlKind.EPG -> "/epg.xml"
        }

        fun refreshQr() {
            val base = resolveBaseUrl()
            if (base == null) {
                binding.imageQrCode.setImageDrawable(null)
                binding.textQrUrl.text = ""
                binding.textQrError.visibility = View.VISIBLE
                binding.textQrError.text = when (accessMode) {
                    AccessMode.LOCAL -> activity.getString(R.string.qr_error_no_lan_ip)
                    AccessMode.REMOTE -> activity.getString(R.string.qr_error_no_remote_url)
                }
                return
            }
            val url = "${base.trimEnd('/')}${pathFor(urlKind)}"
            binding.textQrError.visibility = View.GONE
            binding.textQrUrl.text = url
            val bitmap = QrCodeHelper.encode(url, QR_SIZE_PX)
            if (bitmap != null) {
                binding.imageQrCode.setImageBitmap(bitmap)
            } else {
                binding.imageQrCode.setImageDrawable(null)
                binding.textQrError.visibility = View.VISIBLE
                binding.textQrError.text = activity.getString(R.string.qr_error_encode_failed)
            }
        }

        fun updateAccessUi() {
            val isLocal = accessMode == AccessMode.LOCAL
            binding.textLocalHelper.visibility = if (isLocal) View.VISIBLE else View.GONE
            binding.layoutRemoteSection.visibility = if (isLocal) View.GONE else View.VISIBLE
            refreshQr()
        }

        binding.toggleAccessMode.addOnButtonCheckedListener { _: MaterialButtonToggleGroup, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            accessMode = when (checkedId) {
                R.id.buttonAccessRemote -> {
                    persistRemoteUrl()
                    AccessMode.REMOTE
                }
                else -> AccessMode.LOCAL
            }
            updateAccessUi()
        }

        binding.toggleUrlType.addOnButtonCheckedListener { _: MaterialButtonToggleGroup, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            urlKind = when (checkedId) {
                R.id.buttonUrlEpg -> UrlKind.EPG
                else -> UrlKind.PLAYLIST
            }
            refreshQr()
        }

        binding.editRemoteBaseUrl.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (accessMode == AccessMode.REMOTE) {
                    persistRemoteUrl()
                    refreshQr()
                }
            }
        })

        binding.buttonHowItWorks.setOnClickListener {
            howItWorksExpanded = !howItWorksExpanded
            binding.layoutHowItWorksSteps.visibility =
                if (howItWorksExpanded) View.VISIBLE else View.GONE
            binding.buttonHowItWorks.text = activity.getString(
                if (howItWorksExpanded) R.string.qr_how_it_works_hide else R.string.qr_how_it_works,
            )
        }

        val dialog = AlertDialog.Builder(activity)
            .setView(binding.root)
            .create()

        binding.buttonQrClose.setOnClickListener { dialog.dismiss() }

        dialog.setOnDismissListener { persistRemoteUrl() }

        updateAccessUi()
        binding.buttonAccessLocal.requestFocus()
        dialog.show()
    }

    companion object {
        private const val QR_SIZE_PX = 512
    }
}
