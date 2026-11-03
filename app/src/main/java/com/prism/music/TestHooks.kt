package com.prism.music

/**
 * What Prism's in-app tests (app/src/androidTest, `PrismTestRunner`) set before Prism starts. Nothing sets them in a
 * real run, where they stay null.
 */
object TestHooks {
    /** Answers Prism's network requests in place of the internet (recorded YouTube and lyrics answers). */
    @Volatile var network: okhttp3.Interceptor? = null

    /**
     * What goes between a phone and a Prism TV playing along of a song (normally only YouTube songs:
     * [com.prism.music.playback.together.fromPeer]): the tests' silent tones, files on the test device, stand in for them.
     */
    @Volatile var prismTvSong: ((com.prism.music.data.model.Song) -> com.prism.music.data.model.Song?)? = null
}
