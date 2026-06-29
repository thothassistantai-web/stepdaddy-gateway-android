package com.thothassistant.stepdaddy.gateway.ui

import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.thothassistant.stepdaddy.gateway.GatewayEnvironment
import com.thothassistant.stepdaddy.gateway.R
import com.thothassistant.stepdaddy.gateway.databinding.DialogQrCodeBinding
import com.thothassistant.stepdaddy.gateway.network.GatewayUrlBuilder
import com.thothassistant.stepdaddy.gateway.network.NetworkAccessMode

class QrCodeDialogController(
    private val activity: AppCompatActivity,
    private val environment: GatewayEnvironment,
    private val tivimateLaunchCoordinator: TiviMateLaunchCoordinator? = null,
) {
    private enum class UrlKind { PLAYLIST, EPG }

    fun show() {
        val binding = DialogQrCodeBinding.inflate(LayoutInflater.from(activity))
        val mode = environment.networkAccessMode
        var urlKind = UrlKind.PLAYLIST

        binding.toggleAccessMode.visibility = View.GONE
        binding.layoutRemoteSection.visibility = View.GONE

        fun refreshQr() {
            when (mode) {
                NetworkAccessMode.DEFAULT -> {
                    binding.textLocalHelper.visibility = View.VISIBLE
                    binding.textLocalHelper.text = if (urlKind == UrlKind.EPG &&
                        !environment.gatewayEpgEnabled
                    ) {
                        activity.getString(R.string.qr_external_epg_helper)
                    } else {
                        activity.getString(R.string.qr_default_helper)
                    }
                    if (urlKind == UrlKind.EPG) {
                        val epgUrl = GatewayUrlBuilder.epgQrUrl(environment)
                        if (epgUrl.isNullOrBlank()) {
                            binding.imageQrCode.setImageDrawable(null)
                            binding.textQrUrl.text = ""
                            binding.textQrError.visibility = View.VISIBLE
                            binding.textQrError.text = activity.getString(R.string.qr_error_no_epg_url)
                            return
                        }
                        binding.textQrError.visibility = View.GONE
                        binding.textQrUrl.text = epgUrl
                        val bitmap = QrCodeHelper.encode(epgUrl, QR_SIZE_PX)
                        binding.imageQrCode.setImageBitmap(bitmap)
                        return
                    }
                    binding.imageQrCode.setImageDrawable(null)
                    binding.textQrUrl.text = GatewayUrlBuilder.tivimatePlaylistUrl(environment)
                    binding.textQrError.visibility = View.GONE
                    return
                }
                NetworkAccessMode.LOCAL -> {
                    binding.textLocalHelper.visibility = View.VISIBLE
                    binding.textLocalHelper.text = activity.getString(R.string.qr_local_helper)
                }
                NetworkAccessMode.REMOTE -> {
                    binding.textLocalHelper.visibility = View.VISIBLE
                    binding.textLocalHelper.text = activity.getString(R.string.qr_remote_token_note)
                    binding.layoutRemoteSection.visibility = View.VISIBLE
                    binding.editRemoteBaseUrl.setText(environment.remoteGatewayUrl)
                    binding.editRemoteBaseUrl.isEnabled = false
                    binding.cardPortForwardWarning.visibility = View.GONE
                    binding.buttonHowItWorks.visibility = View.GONE
                }
            }

            val url = when (urlKind) {
                UrlKind.PLAYLIST -> {
                    val base = GatewayUrlBuilder.qrBaseUrl(environment)
                    if (base == null) {
                        binding.imageQrCode.setImageDrawable(null)
                        binding.textQrUrl.text = ""
                        binding.textQrError.visibility = View.VISIBLE
                        binding.textQrError.text = when (mode) {
                            NetworkAccessMode.LOCAL -> activity.getString(R.string.qr_error_no_lan_ip)
                            NetworkAccessMode.REMOTE -> activity.getString(R.string.qr_error_no_remote_url)
                            else -> ""
                        }
                        return
                    }
                    GatewayUrlBuilder.appendAccessToken(
                        GatewayUrlBuilder.tivimatePlaylistUrl(environment),
                        if (mode == NetworkAccessMode.REMOTE) environment.remoteAccessToken else "",
                    )
                }
                UrlKind.EPG -> {
                    val epgUrl = GatewayUrlBuilder.epgQrUrl(environment)
                    if (epgUrl.isNullOrBlank()) {
                        binding.imageQrCode.setImageDrawable(null)
                        binding.textQrUrl.text = ""
                        binding.textQrError.visibility = View.VISIBLE
                        binding.textQrError.text = activity.getString(R.string.qr_error_no_epg_url)
                        return
                    }
                    if (epgUrl.startsWith("https://") ||
                        (epgUrl.startsWith("http://") && !epgUrl.contains("127.0.0.1"))
                    ) {
                        epgUrl
                    } else {
                        GatewayUrlBuilder.appendAccessToken(
                            epgUrl,
                            if (mode == NetworkAccessMode.REMOTE) environment.remoteAccessToken else "",
                        )
                    }
                }
            }

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

        binding.toggleUrlType.addOnButtonCheckedListener { _, checkedId, isChecked ->
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
            override fun afterTextChanged(s: Editable?) = Unit
        })

        val dialog = AlertDialog.Builder(activity)
            .setView(binding.root)
            .create()

        binding.buttonQrClose.setOnClickListener { dialog.dismiss() }
        if (tivimateLaunchCoordinator != null) {
            binding.buttonQrLaunchTivimate.setOnClickListener {
                dialog.dismiss()
                tivimateLaunchCoordinator.launchOrPromptInstall()
            }
        } else {
            binding.buttonQrLaunchTivimate.visibility = View.GONE
        }
        binding.buttonHowItWorks.visibility = View.GONE

        refreshQr()
        binding.buttonUrlPlaylist.requestFocus()
        dialog.show()
    }

    companion object {
        private const val QR_SIZE_PX = 512
    }
}
