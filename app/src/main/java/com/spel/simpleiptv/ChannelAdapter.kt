package com.spel.simpleiptv

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
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
        val scanMark: TextView = view.findViewById(R.id.channelScanMark)
        val star: TextView = view.findViewById(R.id.channelStar)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_channel, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val channel = items[position]
        val state = scanStatus(channel)

        holder.name.text = channel.name
        holder.badge.text = when (state) {
            ChannelScanStatus.WORKING -> "✓"
            ChannelScanStatus.NOT_WORKING -> "✕"
            ChannelScanStatus.UNCERTAIN -> "?"
            ChannelScanStatus.NOT_TESTED -> channel.name.trim().take(2).uppercase().ifBlank { "TV" }
        }
        holder.scanMark.text = when (state) {
            ChannelScanStatus.WORKING -> "Working"
            ChannelScanStatus.NOT_WORKING -> "Offline"
            ChannelScanStatus.UNCERTAIN -> "Uncertain"
            ChannelScanStatus.NOT_TESTED -> ""
        }
        holder.subtitle.text = listOf(channel.category, channel.country, channel.language).filter { it.isNotBlank() }.joinToString(" • ").ifBlank { if (state == ChannelScanStatus.NOT_TESTED) "Live channel" else "Scan result" }
        holder.star.text = if (isFavorite(channel)) "★" else "☆"
        holder.itemView.setOnClickListener { onClick(channel) }
        holder.star.setOnClickListener {
            onFavorite(channel)
            // The parent refreshes the list after favorites change.
        }
    }
}