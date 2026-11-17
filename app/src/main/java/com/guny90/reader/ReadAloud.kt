package com.guny90.reader

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.text.BreakIterator
import java.util.Locale

/**
 * Reads a chapter aloud sentence by sentence with the device's text-to-speech engine
 * (e.g. Google's, with offline voices). Only the next sentence or two are queued, so pausing,
 * changing speed or jumping elsewhere is a simple stop + start.
 *
 * Use from the main thread; all [Listener] calls arrive on the main thread.
 */
class ReadAloud(context: Context, private val listener: Listener) {

    interface Listener {
        /** Started speaking [range] (character offsets in the chapter text). */
        fun onSentence(range: IntRange)
        /** Reached [offset] inside the current sentence (word by word, if the engine reports it). */
        fun onProgress(offset: Int)
        /** Finished the last sentence of the chapter. */
        fun onChapterEnd()
        fun onError(message: String)
        /** No voice for the book's language: reading goes on with another voice. */
        fun onMissingVoice(locale: Locale, downloadable: Boolean)
    }

    private val handler = Handler(Looper.getMainLooper())
    private val whenReady = ArrayList<() -> Unit>()
    private var ready = false
    private var failed = false

    private val tts = TextToSpeech(context.applicationContext) { status ->
        handler.post {
            if (status == TextToSpeech.SUCCESS) {
                ready = true
                whenReady.forEach { it() }
            } else {
                failed = true
                listener.onError(NO_ENGINE)
            }
            whenReady.clear()
        }
    }

    /** 1.0 is normal speed. Applies to sentences queued after the change. */
    var rate = 1f

    // State of the current reading run. A new run (start/stop) increments [generation],
    // which makes the engine's callbacks for older utterances harmless.
    private var generation = 0
    private var text = ""
    private var sentences: BreakIterator = BreakIterator.getSentenceInstance()
    private var nextFrom = 0
    private var pending = 0
    private var endReached = false
    private val warned = HashSet<Locale>()

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String) = onMain(id) { range ->
                listener.onSentence(range)
                queueNext()
            }

            override fun onDone(id: String) = onMain(id) {
                pending--
                if (endReached && pending <= 0) {
                    generation++
                    listener.onChapterEnd()
                }
            }

            override fun onRangeStart(id: String, start: Int, end: Int, frame: Int) = onMain(id) { range ->
                listener.onProgress(range.first + start)
            }

            override fun onError(id: String, errorCode: Int) = onMain(id) {
                generation++
                tts.stop()
                listener.onError(errorMessage(errorCode))
            }

            @Deprecated("Deprecated in Java")
            override fun onError(id: String) = onError(id, TextToSpeech.ERROR)
        })
    }

    /** Reads [chapterText] from offset [from] to the end, then calls [Listener.onChapterEnd]. */
    fun start(chapterText: CharSequence, from: Int, locale: Locale) {
        val gen = ++generation
        text = chapterText.toString()
        sentences = BreakIterator.getSentenceInstance(locale).also { it.setText(text) }
        nextFrom = from.coerceIn(0, text.length)
        pending = 0
        endReached = false
        afterInit {
            if (gen != generation) return@afterInit
            tts.stop()
            useLanguage(locale)
            tts.setSpeechRate(rate)
            repeat(LOOKAHEAD) { queueNext() }
            if (endReached && pending == 0) { // nothing left to read in this chapter
                generation++
                listener.onChapterEnd()
            }
        }
    }

    fun stop() {
        generation++
        if (ready) tts.stop()
    }

    /** Says one word or phrase (dictionary popup), interrupting anything else. */
    fun speakWord(word: String, locale: Locale) {
        val gen = ++generation
        afterInit {
            if (gen != generation) return@afterInit
            useLanguage(locale)
            tts.setSpeechRate(rate)
            tts.speak(word, TextToSpeech.QUEUE_FLUSH, null, "word")
        }
    }

    fun shutdown() {
        generation++
        handler.removeCallbacksAndMessages(null)
        tts.shutdown()
    }

    private fun afterInit(action: () -> Unit) {
        when {
            ready -> action()
            failed -> listener.onError(NO_ENGINE)
            else -> whenReady += action
        }
    }

    private fun useLanguage(locale: Locale) {
        val available = tts.isLanguageAvailable(locale)
        if (available != TextToSpeech.LANG_NOT_SUPPORTED) tts.language = locale
        if (available < TextToSpeech.LANG_AVAILABLE && warned.add(locale)) {
            listener.onMissingVoice(locale, downloadable = available == TextToSpeech.LANG_MISSING_DATA)
        }
    }

    /** Queues the next sentence of the current run. */
    private fun queueNext() {
        if (endReached) return
        val range = nextSentence()
        if (range == null) {
            endReached = true
            return
        }
        val id = "$generation:${range.first}:${range.last}"
        tts.speak(text.substring(range.first, range.last + 1), TextToSpeech.QUEUE_ADD, null, id)
        pending++
    }

    /** The next sentence after [nextFrom] that has something to say, trimmed. */
    private fun nextSentence(): IntRange? {
        val maxLength = minOf(TextToSpeech.getMaxSpeechInputLength(), MAX_CHUNK)
        while (nextFrom < text.length) {
            var start = nextFrom
            var end = sentences.following(nextFrom)
            if (end == BreakIterator.DONE || end > text.length) end = text.length
            if (end - start > maxLength) {
                // Very long "sentence" (a list or a paragraph without full stops): cut at a space.
                val limit = start + maxLength
                val space = text.lastIndexOf(' ', limit)
                end = if (space > start + maxLength / 2) space else limit
            }
            nextFrom = end
            while (start < end && text[start].isWhitespace()) start++
            while (end > start && text[end - 1].isWhitespace()) end--
            val chunk = text.substring(start, end)
            if (chunk != "[image]" && chunk.any { it.isLetterOrDigit() }) return start until end
        }
        return null
    }

    /** Runs [action] on the main thread with the utterance's range if it belongs to the current run. */
    private fun onMain(id: String, action: (IntRange) -> Unit) {
        val parts = id.split(':')
        if (parts.size != 3) return
        val gen = parts[0].toIntOrNull() ?: return
        val range = (parts[1].toIntOrNull() ?: return)..(parts[2].toIntOrNull() ?: return)
        handler.post { if (gen == generation) action(range) }
    }

    private fun errorMessage(code: Int) = when (code) {
        TextToSpeech.ERROR_NOT_INSTALLED_YET ->
            "The voice for this language isn't downloaded yet. Download it in the text-to-speech settings."
        TextToSpeech.ERROR_NETWORK, TextToSpeech.ERROR_NETWORK_TIMEOUT ->
            "This voice needs the internet. Download it for offline use in the text-to-speech settings."
        TextToSpeech.ERROR_OUTPUT ->
            "Can't play sound. Check that a speaker or headphones are connected."
        else -> "Text-to-speech failed (error $code)."
    }

    companion object {
        const val NO_ENGINE = "No text-to-speech engine is available.\n" +
            "Install one (e.g. Speech Services by Google) and choose it in the text-to-speech settings."
        /** Sentences queued ahead, so there is no pause between them. */
        private const val LOOKAHEAD = 2
        /** Long chunks would delay the underline and page turns. */
        private const val MAX_CHUNK = 1000

        /** System screen for choosing the engine and downloading voices. */
        const val SETTINGS_ACTION = "com.android.settings.TTS_SETTINGS"
    }
}
