package com.forcenetwork.app.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.forcenetwork.app.R
import com.forcenetwork.app.databinding.ItemNetworkBinding
import com.forcenetwork.app.util.WifiHelper

/**
 * RecyclerView adapter for displaying WiFi networks.
 */
class NetworkAdapter(
    private val onNetworkClick: (WifiHelper.WifiNetwork) -> Unit
) : ListAdapter<WifiHelper.WifiNetwork, NetworkAdapter.NetworkViewHolder>(NetworkDiffCallback()) {

    private var preferredSsid: String? = null

    fun setPreferredNetwork(ssid: String?) {
        preferredSsid = ssid
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): NetworkViewHolder {
        val binding = ItemNetworkBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return NetworkViewHolder(binding)
    }

    override fun onBindViewHolder(holder: NetworkViewHolder, position: Int) {
        holder.bind(getItem(position), preferredSsid)
    }

    inner class NetworkViewHolder(
        private val binding: ItemNetworkBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            binding.root.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onNetworkClick(getItem(position))
                }
            }
        }

        fun bind(network: WifiHelper.WifiNetwork, preferredSsid: String?) {
            binding.tvNetworkName.text = network.ssid
            binding.tvSignalStrength.text = getSignalDescription(network.signalStrength, network.isSecure)
            
            // Show star for preferred network
            binding.ivPreferred.visibility = if (network.ssid == preferredSsid) {
                View.VISIBLE
            } else {
                View.GONE
            }

            // Update wifi icon tint based on signal strength
            val iconTint = when {
                network.signalStrength >= 4 -> R.color.success
                network.signalStrength >= 2 -> R.color.primary
                else -> R.color.warning
            }
            binding.ivWifiIcon.setColorFilter(
                binding.root.context.getColor(iconTint)
            )
        }

        private fun getSignalDescription(strength: Int, isSecure: Boolean): String {
            val signalText = when (strength) {
                4 -> "Excellent"
                3 -> "Good"
                2 -> "Fair"
                1 -> "Weak"
                else -> "Very Weak"
            }
            val securityText = if (isSecure) "Secured" else "Open"
            return "$signalText • $securityText"
        }
    }

    class NetworkDiffCallback : DiffUtil.ItemCallback<WifiHelper.WifiNetwork>() {
        override fun areItemsTheSame(
            oldItem: WifiHelper.WifiNetwork,
            newItem: WifiHelper.WifiNetwork
        ): Boolean {
            return oldItem.ssid == newItem.ssid
        }

        override fun areContentsTheSame(
            oldItem: WifiHelper.WifiNetwork,
            newItem: WifiHelper.WifiNetwork
        ): Boolean {
            return oldItem == newItem
        }
    }
}
