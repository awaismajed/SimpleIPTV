package com.spel.simpleiptv

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.ImageView
import coil.load
import androidx.recyclerview.widget.RecyclerView

enum class ChannelScanStatus { NOT_TESTED, WORKING, NOT_WORKING, UNCERTAIN }

class ChannelAdapter(
    private val items: List<Channel>,
    private val isFavorite: (Channel) -> Boolean,
    private val scanStatus: (Channel) -> ChannelScanStatus,
    private val onFavorite: (Channel) -> Unit,
    private val onClick: (Channel) -> Unit
) : RecyclerView.Adapter<ChannelAdapter.VH>() {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val badge: TextView = view.findViewById(R.id.channelBadge)
        val name: TextView = view.findViewById(R.id.channelName)
        val subtitle: TextView = view.findViewById(R.id.channelSubtitle)
        val logo: ImageView = view.findViewById(R.id.channelLogo)
        val star: TextView = view.findViewById(R.id.channelStar)
        val indicator: TextView = view.findViewById(R.id.channelStatus)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_channel, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val channel = items[position]

        holder.name.text = channel.name
        holder.badge.text = channel.name.trim().take(2).uppercase().ifBlank { "TV" }
        if (channel.logo.startsWith("https://", true) || channel.logo.startsWith("http://", true)) {
            holder.logo.visibility = View.VISIBLE
            holder.logo.load(channel.logo) {
                crossfade(true)
                listener(onError = { _, _ ->
                    holder.logo.visibility = View.GONE
                })
            }
        } else {
            holder.logo.setImageDrawable(null)
            holder.logo.visibility = View.GONE
        }
        val countryName = channel.country.split(';', ',').map { code ->
            val value = code.trim()
            if (value.length == 2) java.util.Locale("", value).displayCountry.takeIf { it.isNotBlank() } ?: value else value
        }.filter { it.isNotBlank() }.joinToString(", ")
        holder.subtitle.text = listOf(countryName, channel.category, channel.language)
            .filter { it.isNotBlank() }.distinct().joinToString(" • ").ifBlank { "Live channel" }
        val state = scanStatus(channel)
        holder.indicator.text = when (state) {
            ChannelScanStatus.WORKING -> "●"
            ChannelScanStatus.NOT_WORKING -> "●"
            ChannelScanStatus.UNCERTAIN -> "◐"
            ChannelScanStatus.NOT_TESTED -> "○"
        }
        holder.indicator.setTextColor(android.graphics.Color.parseColor(when (state) {
            ChannelScanStatus.WORKING -> "#32D47B"
            ChannelScanStatus.NOT_WORKING -> "#FF5964"
            ChannelScanStatus.UNCERTAIN -> "#F4C542"
            ChannelScanStatus.NOT_TESTED -> "#8394A5"
        }))
        holder.star.text = if (isFavorite(channel)) "★" else "☆"
        holder.itemView.setOnClickListener { onClick(channel) }
        holder.star.setOnClickListener {
            onFavorite(channel)
            // The parent refreshes the list after favorites change.
        }
    }
}