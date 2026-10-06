package com.anubhav.app.ui.booking

import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.anubhav.app.R
import com.anubhav.app.data.model.AktivTest
import com.anubhav.app.utils.localized
import com.google.android.material.card.MaterialCardView
import java.util.Locale

class AktivTestAdapter(
    private val onToggle: (AktivTest) -> Unit,
) : RecyclerView.Adapter<AktivTestAdapter.VH>() {
    private var items: List<AktivTest> = emptyList()
    private var selected: Set<Int> = emptySet()

    fun submit(tests: List<AktivTest>, selectedTests: List<AktivTest> = emptyList()) {
        items = tests
        selected = selectedTests.map { it.testKey }.toSet()
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val card = MaterialCardView(parent.context).apply {
            radius = resources.getDimension(R.dimen.list_item_radius)
            cardElevation = 0f
            setCardBackgroundColor(ContextCompat.getColor(context, R.color.card_background))
            strokeWidth = resources.getDimensionPixelSize(R.dimen.list_item_stroke)
            strokeColor = ContextCompat.getColor(context, R.color.stroke_soft)
            isClickable = true
            isFocusable = true
            layoutParams = ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { setMargins(0, 0, 0, resources.getDimensionPixelSize(R.dimen.list_item_gap)) }
        }
        val row = LinearLayout(parent.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(12.dp(), 9.dp(), 12.dp(), 9.dp())
            minimumHeight = 56.dp()
        }
        val labels = LinearLayout(parent.context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val title = TextView(parent.context).apply {
            textSize = 14f
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            maxLines = 2
        }
        val meta = TextView(parent.context).apply {
            textSize = 11f
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
        }
        labels.addView(title)
        labels.addView(meta)
        row.addView(labels)
        val action = TextView(parent.context).apply {
            gravity = Gravity.END
            textSize = 12f
            setTextColor(ContextCompat.getColor(context, R.color.brand_blue))
            setPadding(8.dp(), 0, 0, 0)
        }
        row.addView(action)
        card.addView(row)
        return VH(card, title, meta, action)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        val context = holder.card.context
        val isSelected = selected.contains(item.testKey)
        holder.title.text = item.testName
        holder.meta.text = listOfNotNull(
            item.testCode.takeIf { it.isNotBlank() },
            item.categoryName?.takeIf { it.isNotBlank() },
        ).joinToString(" · ")
        val price = String.format(Locale.forLanguageTag("en-IN"), "₹%,.2f", item.rate)
        holder.action.text = "$price\n${context.localized(if (isSelected) R.string.booking_added_test else R.string.booking_add_test)}"
        holder.card.strokeColor = ContextCompat.getColor(
            context,
            if (isSelected) R.color.accent else R.color.stroke_soft,
        )
        holder.card.setCardBackgroundColor(
            ContextCompat.getColor(
                context,
                if (isSelected) R.color.accent_light else R.color.card_background,
            ),
        )
        holder.card.setOnClickListener { onToggle(item) }
    }

    override fun getItemCount(): Int = items.size

    class VH(
        val card: MaterialCardView,
        val title: TextView,
        val meta: TextView,
        val action: TextView,
    ) : RecyclerView.ViewHolder(card)
}

private fun Int.dp(): Int = (this * android.content.res.Resources.getSystem().displayMetrics.density).toInt()
