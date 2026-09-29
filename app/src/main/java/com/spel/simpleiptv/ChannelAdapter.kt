package com.spel.simpleiptv

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class ChannelAdapter(
    private val items: List<Channel>,
    private val isFavorite: (Channel) -> Boolean,
    private val onFavorite: (Channel) -> Unit,
    private val onClick: (Channel) -> Unit
) : RecyclerView.Adapter<ChannelAdapter.VH>() {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val badge: TextView = view.findViewById(R.id.channelBadge)
        val name: TextView = view.findViewById(R.id.channelName)
        val subtitle: TextView = view.findViewById(R.id.channelSubtitle)
        val star: TextView = view.findViewById(R.id.channelStar)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_channel, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val channel = items[position]
        holder.name.text = channel.name
        holder.badge.text = channel.name.trim().take(2).uppercase().ifBlank { "TV" }
        holder.subtitle.text = "Live channel"
        holder.star.text = if (isFavorite(channel)) "★" else "☆"
        holder.itemView.setOnClickListener { onClick(channel) }
        holder.star.setOnClickListener {
            onFavorite(channel)
            notifyItemChanged(position)
        }
    }
}