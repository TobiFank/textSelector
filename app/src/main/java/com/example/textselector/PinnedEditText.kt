package com.example.textselector

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.Parcel
import android.os.Parcelable
import android.text.Editable
import android.text.Spannable
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.style.BackgroundColorSpan
import android.util.AttributeSet
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.widget.TextView
import androidx.appcompat.widget.AppCompatEditText
import androidx.core.content.ContextCompat
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import java.util.regex.Pattern

class PinnedEditText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.editTextStyle
) : AppCompatEditText(context, attrs, defStyleAttr) {

    // Stores the two boundaries of the current selection.
    private var pinnedStart: Int? = null
    private var pinnedEnd: Int? = null

    // For detecting triple taps.
    private var tapCount = 0
    private var lastTapTime = 0L
    private val tripleTapThreshold = 500L // milliseconds

    // --- New search navigation support ---
    private var searchResults: List<IntRange> = emptyList()
    private var currentSearchIndex: Int = 0

    var selectionChangeListener: ((Int, Int) -> Unit)? = null
    var onSearchCleared: (() -> Unit)? = null
    fun isPinActive(): Boolean = pinnedStart != null && pinnedEnd != null

    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        selectionChangeListener?.invoke(selStart, selEnd)
    }

    private fun handleDoubleTap(offset: Int) {
        val (wordStart, wordEnd) = selectWordAt(offset)

        if (pinnedStart == null || pinnedEnd == null) {
            pinnedStart = wordStart
            pinnedEnd = wordEnd
            setSelection(pinnedStart!!, pinnedEnd!!)
        } else {
            val tapMid = (wordStart + wordEnd) / 2
            val distanceToStart = abs(tapMid - pinnedStart!!)
            val distanceToEnd = abs(tapMid - pinnedEnd!!)
            if (distanceToStart <= distanceToEnd) {
                pinnedStart = wordStart
            } else {
                pinnedEnd = wordEnd
            }
            val newStart = min(pinnedStart!!, pinnedEnd!!)
            val newEnd = max(pinnedStart!!, pinnedEnd!!)
            setSelection(newStart, newEnd)
        }

        if (searchResults.isNotEmpty()) {
            clearSearchHighlights()
        } else {
            onSearchCleared?.invoke()
        }
        invalidate()
    }

    fun clearSelectionPins() {
        pinnedStart = null
        pinnedEnd = null
        val pos = selectionStart
        setSelection(pos, pos)
        invalidate()
        onSearchCleared?.invoke()
    }

    fun clearSearchHighlights(invokeCallback: Boolean = true) {
        val editable = text ?: return
        val searchHighlightColor = ContextCompat.getColor(context, R.color.searchHighlight)
        val spans = editable.getSpans(0, editable.length, BackgroundColorSpan::class.java)
        spans.forEach { span ->
            if (span.backgroundColor == searchHighlightColor) {
                editable.removeSpan(span)
            }
        }
        searchResults = emptyList()
        if (pinnedStart != null && pinnedEnd != null) {
            setSelection(pinnedStart!!, pinnedEnd!!)
        }
        invalidate()
        if (invokeCallback) {
            onSearchCleared?.invoke()
        }
    }

    // Gesture detector for double taps.
    private val gestureDetector =
        GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                val offset = getOffsetForPosition(e.x, e.y)
                handleDoubleTap(offset)
                return true
            }
        })

    init {
        if (text !is Editable) {
            Logger.d("PinnedEditText", "Converting text to Editable")
            setText(Editable.Factory.getInstance().newEditable(text))
        }
        // Disable long press so the double tap isn’t delayed
        gestureDetector.setIsLongpressEnabled(false)
        // Prevent the keyboard from appearing automatically when the view gains focus.
        // (This makes scrolling and taps not open the keyboard by default.)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            showSoftInputOnFocus = false
        } else {
            @Suppress("DEPRECATION")
            setTextIsSelectable(true)
        }
    }

    override fun onFocusChanged(focused: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(focused, direction, previouslyFocusedRect)
        // When losing focus (e.g. when the keyboard is dismissed) we restore the pinned selection.
        if (!focused && pinnedStart != null && pinnedEnd != null) {
            // Post a restore so that when focus returns the selection remains.
            post { setSelection(pinnedStart!!, pinnedEnd!!) }
        }
    }

    // Paint used to draw the "PIN" indicator.
    private val pinIndicatorPaint = Paint().apply {
        color = ContextCompat.getColor(context, R.color.gold_primary)
        textSize = 36f  // adjust as needed
        isAntiAlias = true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // --- Triple tap detection remains ---
        if (event.action == MotionEvent.ACTION_DOWN) {
            val now = System.currentTimeMillis()
            if (now - lastTapTime < tripleTapThreshold) {
                tapCount++
            } else {
                tapCount = 1
            }
            lastTapTime = now
            if (tapCount == 3) {
                clearSelectionPins()
                tapCount = 0
                // Let the default handling update the view as well.
                return super.onTouchEvent(event)
            }
        }
        // Let the gesture detector process the event.
        val gestureConsumed = gestureDetector.onTouchEvent(event)
        // Always call super.onTouchEvent so that the default selection and scrolling work.
        val superConsumed = super.onTouchEvent(event)
        return gestureConsumed || superConsumed
    }


    /**
     * Returns the full boundaries (start, end) of the word at the given text offset.
     */
    private fun selectWordAt(offset: Int): Pair<Int, Int> {
        val textStr = text?.toString() ?: ""
        if (textStr.isEmpty()) return Pair(0, 0)
        var start = offset
        var end = offset
        while (start > 0 && !textStr[start - 1].isWhitespace()) {
            start--
        }
        while (end < textStr.length && !textStr[end].isWhitespace()) {
            end++
        }
        return Pair(start, end)
    }

    fun getSearchResultsCount(): Int = searchResults.size

    fun getCurrentSearchIndex(): Int = if (searchResults.isNotEmpty()) currentSearchIndex + 1 else 0

    fun updateSearch(query: String) {
        Logger.d("PinnedEditText", "updateSearch called with query: '$query'")

        val editable = editableText
        if (editable == null) {
            Logger.e("PinnedEditText", "Text is not editable!")
            return
        }
        Logger.d("PinnedEditText", "Current text content: '${editable.toString()}'")

        clearSearchHighlights(invokeCallback = false)
        Logger.d("PinnedEditText", "Cleared previous highlights")

        if (query.isEmpty()) {
            Logger.d("PinnedEditText", "Empty query, returning")
            return
        }

        val searchHighlightColor = ContextCompat.getColor(context, R.color.searchHighlight)
        Logger.d("PinnedEditText", "Search highlight color: $searchHighlightColor")

        try {
            val regex = Regex(Regex.escape(query), RegexOption.IGNORE_CASE)
            Logger.d("PinnedEditText", "Created regex: ${regex.pattern}")

            val text = editable.toString()
            val matches = regex.findAll(text).toList()
            Logger.d("PinnedEditText", "Found ${matches.size} matches")

            matches.forEach { match ->
                Logger.d("PinnedEditText", "Match at ${match.range}: '${match.value}'")
                editable.setSpan(
                    BackgroundColorSpan(searchHighlightColor),
                    match.range.first,
                    match.range.last + 1,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }

            searchResults = matches.map { it.range }
            Logger.d("PinnedEditText", "Set searchResults to ${searchResults.size} ranges")

            if (searchResults.isNotEmpty()) {
                currentSearchIndex = 0
                val firstRange = searchResults[0]
                Logger.d("PinnedEditText", "Selecting first match: $firstRange")
                setSelection(firstRange.first, firstRange.last + 1)
            }

            postInvalidate()
            Logger.d("PinnedEditText", "Search update complete")

        } catch (e: Exception) {
            Logger.e("PinnedEditText", "Search failed", e)
            e.printStackTrace()
        }
    }

    fun nextSearchResult() {
        if (searchResults.isNotEmpty()) {
            currentSearchIndex = (currentSearchIndex + 1) % searchResults.size
            val range = searchResults[currentSearchIndex]
            setSelection(range.first, range.last + 1)
            bringPointIntoView(range.first)
        }
    }

    fun previousSearchResult() {
        if (searchResults.isNotEmpty()) {
            currentSearchIndex =
                if (currentSearchIndex - 1 < 0) searchResults.size - 1 else currentSearchIndex - 1
            val range = searchResults[currentSearchIndex]
            setSelection(range.first, range.last + 1)
            bringPointIntoView(range.first)
        }
    }

    override fun onSaveInstanceState(): Parcelable? {
        val superState = super.onSaveInstanceState()
        val ss = SavedState(superState)
        ss.pinnedStart = pinnedStart ?: -1
        ss.pinnedEnd = pinnedEnd ?: -1
        return ss
    }

    override fun onRestoreInstanceState(state: Parcelable?) {
        if (state !is SavedState) {
            super.onRestoreInstanceState(state)
            return
        }
        super.onRestoreInstanceState(state.superState)
        pinnedStart = if (state.pinnedStart != -1) state.pinnedStart else null
        pinnedEnd = if (state.pinnedEnd != -1) state.pinnedEnd else null
        if (pinnedStart != null && pinnedEnd != null) {
            setSelection(pinnedStart!!, pinnedEnd!!)
        }
        invalidate()
    }

    internal class SavedState : BaseSavedState {
        var pinnedStart: Int = -1
        var pinnedEnd: Int = -1

        constructor(superState: Parcelable?) : super(superState)
        private constructor(parcel: Parcel) : super(parcel) {
            pinnedStart = parcel.readInt()
            pinnedEnd = parcel.readInt()
        }

        override fun writeToParcel(out: Parcel, flags: Int) {
            super.writeToParcel(out, flags)
            out.writeInt(pinnedStart)
            out.writeInt(pinnedEnd)
        }

        companion object CREATOR : Parcelable.Creator<SavedState> {
            override fun createFromParcel(parcel: Parcel): SavedState {
                return SavedState(parcel)
            }

            override fun newArray(size: Int): Array<SavedState?> {
                return arrayOfNulls(size)
            }
        }
    }

}
