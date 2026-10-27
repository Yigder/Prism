# Changelog

What changed in each release. Older releases are described on the [Releases page](https://github.com/Yigder/Prism/releases).

## 1.7.6

Home, Library and Search your way, and a lighter, faster Prism. The fifth customization update, and a performance one:
Performance mode for older phones, about 200 MB less memory at start, and quicker lists and Search.

### You asked for it
- **Home opens straight away** (#126): the last Home is kept on the phone and shows at once when Prism starts, while the
  new one loads behind it, instead of a loading spinner. It goes when you sign out or switch accounts.
- **Full-bleed video** (#120, Settings → Customize Prism → Player → Cover, or the new button on the video): a music video fills the top
  of the player edge to edge and fades into the background, as on YouTube Music.
- **The lyric line on the player, centered and on two lines** (#121, Settings → Customize Prism → Lyrics → More options → Where the lines go): the line
  over the progress bar can sit in the middle, and a long line can wrap onto a second line instead of being cut off.
- **Featured and Community playlists in Search** (#130): the Playlists filter is now two, YouTube Music's own playlists
  and people's.
- **Quick picks and Speed dial in any language** (#131): YouTube's Quick picks under another language's title stop at
  each column like Prism's, YouTube's own Speed dial is found in any language, and Customize Home → "Speed dial: YouTube
  Music's only" leaves out the tiles Prism adds from your plays.

### Performance mode
For phones that found 1.7 heavier than 1.6 (#103, #132, #134): **Settings → Performance mode**, one switch that draws
Prism about as lightly as 1.6 did. It pauses blur and frosted glass, the frost behind pages, moving and illustrated
backgrounds (pages are plain), animated covers, the visualizer, a drifting player background, focus blur and glow in the
lyrics, the picture at the top of Home, and animations. Every feature keeps working, nothing you picked is changed, and
it all comes back as it was when you turn it off. Phones on Android 9 or older, or with 4 GB of memory or less, are
offered it once on Home. On the Android 9 emulator, scrolling Home with music playing took half the processor time and
frames drew 4–5× faster with it on.

### Faster everywhere
- Pages read YouTube's answers into shelves and lists off the screen's thread (Home, albums, playlists, artists,
  podcasts, moods, and every search): on a slow phone a long playlist held the screen up for a few hundred milliseconds.
- Covers kept on the phone are looked up from memory, without working out each picture's file name again on every
  frame of a scrolling list.
- The player's volume and output are asked of Android in the background, and the moods ranking is worked out there too.
- **Library and Settings open faster after an update**: the list of Prism's code that Android compiles ahead at install
  now covers them too (since 1.7.4 it only had Home, Search and the player).
- Playback diagnostics now note Prism's memory every half minute, by kind, to find what grows on phones that close Prism.
- **Animated covers use far less memory**: their player kept up to 50 seconds of video loaded ahead (looping the same
  few seconds over and over), about 50 MB on a budget phone; it now keeps 10 seconds at most (8 MB). And a cover is no
  bigger than the screen: a 720 px phone plays the 720 px clip, not the 1080 px one. Prism's memory on a budget phone
  with an animated cover playing went from 77 to 53 MB.

### Settings, easier to find
Settings are sorted by what they do, and every page is shorter. Nothing was removed; Settings search finds everything
at its new place.
- **Customize Prism** holds everything about how Prism looks, in one place: Looks, Color, Text, Icons & shapes, Glass &
  backgrounds, then each part of Prism (Tabs & mini player, Home & Library, the Player, the Lyrics). The live preview
  is at its top once, instead of pinned over every look page.
- **Playback & sound**, **Lyrics & Sing** (sources, language, Sing), **Controls & gestures** (the player's swipes and
  taps, holding previous / next, haptics, the notification's and car's buttons, Search's keyboard),
  **Recommendations & Replay**, **Downloads, storage & data** and **Car & devices** (Android Auto, speakers and TVs,
  Discord), then **Backup & about**.
- **Every "on mobile data" choice is on one page** (Downloads, storage & data → Mobile data); they were on six.
- Less-used groups (the player's title and visualizer, where lyric lines sit…) are folded under **More options**.
- Color's preview, still pinned while you scroll, no longer lets the page show through behind it.

### Search, redesigned (#124)
Prism's own design, not YouTube's page:
- **Search by lyrics, in the search box itself**: type a line from a song ("i was too busy tryna find you with someone else") and
  the song it's from shows under **From the lyrics**, with the line as the song has it, first when the line is nearly
  word for word. Lyrics already on your phone are looked through first (offline too), then Genius's lyrics. They show
  only when Search didn't find the song by its name, and only for the words together as typed.
- **Before you type**: what you opened lately by its picture, your recent searches, your artists as a row of photos,
  and the moods and genres as Prism-drawn tiles (their color, a pattern, the split light). **Arrange this page**
  shows, hides and orders the sections.
- **While typing, only suggestions**: "Search for …", the completions, and the artists, songs and albums YouTube Music
  suggests with their pictures (a tap opens or plays one at once). The chips and results wait until you search, or put
  the keyboard away, so they no longer pile up under the suggestions.
- **One row of chips**: "On this phone" is now the last chip, beside Songs, Videos, Albums and the rest, instead of a
  row of its own above them.
- **In your library** and **On this phone**: results that are already yours (liked songs, your playlists, albums and
  artists) and matching music files and downloads come right after the top result.

### Home your way
- **Pin to Home**: hold any album, playlist, artist or mix and pick "Pin to Home": it becomes a section of its own at
  the top of Home, its songs ready to play, its name opening its page.
- **Sections from your own listening** (Customize Home → Add a section → Your own): Liked this month, Downloads you
  haven't played, and Most played in each year you've listened.
- **Rename any section** from its ⋮ in Customize Home.
- **Leave off music video or podcast sections**: YouTube Music's own sections of those kinds stay off Home.
- **The top of Home** (Customize Home → Top of Home): the greeting as now, just the buttons, the greeting over a
  picture (one of the background designs or your own photo), or over the playing cover, frosted; Short, Medium or Tall.

### Covers you make
- **Make a cover** (a playlist's picture → Make a cover): its name in large type over two colors of your choosing, with a
  pattern from the backgrounds and the split light around it if you like, seen as you make it.
- **Your own photo of an artist** (an artist page's ⋯ → Use your own photo): on their page and in Library.
- Both stay on this phone (and in backups); YouTube Music keeps its own pictures.

### Library your way
- **Playlist folders**: hold a playlist → "Move to a folder" (or make a new one there). Folders sit at the top of
  Library → Playlists; a folder's page renames it or takes it apart. Kept on the phone, and in backups.
- **Pin to the top**: hold a playlist, album or artist in Library to keep it first in its tab.
- **Each tab its own view**: Playlists, Albums and Artists can each be a list or a grid (the button at the top of
  Library switches the tab you're on).
- **Covers across** (Auto, 2, 3 or 4) for the grid, and **Details under each one** to hide song counts, owners and years.

### Tabs your way (Settings → Customize Prism → Tabs & mini player)
- **More tabs**: Downloads, Liked songs and Podcasts can be tabs too, up to six in all.
- **Rename any tab**: tap its name in Tabs.
- **Words only**: the tabs' names without icons (they shrink to fit five or six).
- **Height** (Compact, Standard, Tall) and **Background** (as the glass, see-through or solid) for the tab bar.
- **Swipe between tabs** (off by default): a sideways swipe on a tab's page goes to the tab beside it; rows that
  scroll sideways keep their swipes.
- **Mini player joined to the tab bar**: one floating piece of glass, the mini player on top of the tabs.

### Playlists without an account
- **Keep a YouTube playlist in your library without signing in**: its page's "Add to library" (or a long press) keeps
  it on the phone, in Library → Playlists, opened fresh from YouTube each time. Mixes, which change every time, aren't kept.
- **Save a copy as a Prism playlist** (any YouTube playlist's ⋯ or long press, signed in or not): every song of it, in a
  playlist of your own on the phone that stays as it is.
- **The queue's Save works without an account too**, as a Prism playlist.
- **Library → Playlists → Open a link**: paste a playlist's YouTube or YouTube Music link to open it, no share menu needed.

### Podcasts
- **Episode details**: an episode's ⋯ (or a tap on its description) shows all of it, with working links, its date and
  length, and the show it's from. A show YouTube gives no description says so instead of showing nothing.

### Fixes
- **About 200 MB less memory in the first minute after Prism opens** (likely #103). With a big library, working out your
  artists' genres from what was already kept on the phone ran a text pattern over every song of every artist, and each
  of those checks held a little memory until Android tidied up: on the test phones Prism climbed from ~190 to ~340 MB
  of native memory for most of a minute after opening, enough for Android to close it on a phone with little memory.
  Now each genre name is worked out once; it stays near 110 MB. Reading saved lyrics back (for every download, at each
  start) no longer builds its patterns again each time either.
- **A search no longer shows "The coroutine scope left the composition"** as its error when it was interrupted by the
  next letter typed, or by leaving the page.
- **The Card mini player is glass like the tab bar** under it, with the tab bar's own background (see-through or
  solid), and its progress line runs along its bottom edge. With Frosted Glass off it used to turn into a solid bar
  with the progress line on top.
- **Podcast episodes no longer turn LIVE** a few seconds in (#128). Episodes recorded from a live broadcast were taken for
  streams still on air, so the length disappeared and the progress bar stopped seeking. They play as normal episodes now.
- **Each local song shows its own cover** (#129). Android files untagged songs from one folder (and other mixes) as one
  album, so one file's cover went on all of them. Those songs now show the cover inside each file.
- **Player, Layout & navigation and Lyrics are listed once** in Settings (#127), under Customize Prism, no longer twice.
- US spelling in the last few places: Favorites, Centered.

## 1.7.5

The fourth customization update: lyrics, your way.

### Lyrics, your way
Settings → Lyrics (with a live preview at the top), or the player's ⋯ → Player settings. Every choice shows an example of
itself, drawn the way the lyrics are, and nothing changes until you pick something: the lyrics look as they did.
- **Six styles** for the line being sung: **Prism** (each word grows bolder as it's sung, as before), **Classic karaoke**
  (color fills the words), **Bold card** (big bold lines on a color from the cover, changing with each song), **Glow**
  (the sung words glow, in the shape of their letters), **Typewriter** (words appear as they're sung) and **Calm** (no animation, the line just
  brightens). Lyrics timed by the line rather than the word are filled in at a steady pace in Karaoke and Typewriter.
- **Colors** for the sung line and for the others: white, your light, the accent, the cover's color, or your own from the
  color picker.
- **The look:** weight (Thin to Bold), line spacing, how dim the other lines are, focus blur as a strength (from off to
  strong; the old switch carries over), the size of background vocals, a shadow for photo backgrounds, and capitals.
- **Where the lines go:** the sung line near the top (as before), a third down or in the middle, and how many of the lines
  to come show (all, three, one or none).
- **Translations and romanized lines:** over or under each line, their size, color and italics.
- **A background for the lyrics:** as the player's (as before), plain dark, the cover blurred, the animated cover, the
  cover's color, or your own color or gradient. **Immersive mode** picks its own from the same list (the cover, as before).
- **Same look everywhere:** the current-lyric line above the progress bar and immersive mode follow these settings (on by
  default; with every setting as it comes they look as before). Off, they keep Prism's own look.
- All of it is part of your Looks, Reset look and backups.

### Also new (from GitHub)
- **History** in Library (#118): what you played, newest first under day headings, with the time and repeats in a row
  folded into one ("3×"). "On this phone" is Prism's own (kept on the phone); signed in, "Your account" shows YouTube
  Music's history, plays from the YouTube Music app and website included. A Library start tab too.
- **A song and its video as one** (#111, Settings → Library & Replay, on): liked both a song and its music video? The
  lists show it once, as the song. The video stays liked on YouTube; nothing is changed there. Live takes and other
  recordings stay apart.
- **Prism's own volume** (#116, Settings → Player → "The volume slider changes"): the player's volume slider can change
  Prism's volume instead of the phone's, so other apps stay as loud as they are. It works with crossfades, on Bluetooth
  and offline; casting still uses the speaker's own volume.
- **Hide the status bar** (#115, Settings → Layout & navigation → Screen): more room for Prism; swipe down from the top
  to see it for a moment. Part of your Looks.
- **Tidier settings pages** (#119): the explanations on every settings page now sit behind an ⓘ beside the setting,
  choice or group they explain, as the longer details already did, so each page shows just its settings until you ask.
  This includes the Color page's notes, its "Changes" lists and the three parts' descriptions. What's always shown is
  only what's happening now: space used, an error, why something is still or greyed out.
- **Library's tiles** are picked and ordered from a new edit button at the top right of Library (beside sync), instead of
  an "Edit tiles" tile at the end of them.

### Lighter on the battery
Nothing works differently; Prism just does less that nobody sees.
- With the full player open, the screen under it (an animated background, the playing bars) holds still instead of
  being drawn every frame behind the player: about half the work while the player is up, in our tests.
- The player's clocks (the progress bar, volume, where the sound is going, the sleep timer) stop ticking while Prism is
  out of sight, and catch up the moment it's back.
- Animated backgrounds, the playing bars and loading bars redraw only themselves as they move, not the page around them.
- Paused lyrics no longer redraw on every frame of the screen (and no longer keep a 120 Hz screen at 120 Hz).
- Song rows only change when their own like or download marks do, not at every step of every download.
- With the screen off, widgets wait for it to come back on to move their progress bars.
- Lossless sync's look every few hours only runs while the lossless add-on is on.
- The stall watcher (Diagnostics) sleeps for good once it's turned off.

## 1.7.4

The third customization update: the player, your way. And a faster Prism.

### Faster
- **Prism no longer runs slow after an update.** After every install or update, Android ran much of Prism's code in
  its slow interpreter until it got round to compiling the app, often overnight while charging, and only the parts it
  had seen used. Prism now carries a startup profile (the code starting up, Home, Search, the player and the lyrics
  use), and **updates from Prism's own update banner are compiled while they install** (Android 9 and later), the way
  the Play Store does it for its apps. That starts with the update after this one: this one is installed by the
  Prism you have now. Installed some other way, Prism is compiled from the profile at the phone's next maintenance.
  On an Android 13 test device, starting Prism compiled took 2.4 s instead of 3.9 s.
- **Smoother while music plays.** The player and the mini player were rebuilt four times a second just to move the
  progress bar; now only the bar redraws. The full player is noticeably lighter to open, close and swipe.
- **Search on this phone** (your music files and downloads) is matched off the screen's thread, so typing doesn't stall
  with a big library, and lists of covers do less work as they scroll.
- **Home scrolls more smoothly** with a lot of listening history: the Replay card no longer reads the whole year from
  the database (and jumps the list) every time it scrolls back into view, and Made for you no longer unpacks every mix
  on the screen's thread.
- **Diagnostics see stutters too:** with Settings → About → Diagnostics on, anything that holds up the screen for more
  than a couple of frames is noted with where it happened, so a log shared from a slow phone says what to fix (#100).

### The player, your way
Settings → Appearance → Player, or the player's ⋯ → Player settings. Every choice shows what it looks like.
- **Layouts:** Standard, **Cover** (a big cover; tap it for the controls), **Lyrics** (opens on the lyrics, a small
  cover beside the title), **Minimal** (the title and play; gestures do the rest), **Vinyl** (the cover as a record's
  label, turning as it plays and slowing to a stop on pause, with a tonearm if you like) and **Cassette** (the cover on a
  tape whose reels turn as it plays).
- **A visualizer**, off unless you pick one: **Bars** along the bottom, a **Wave** behind the title, a **Ring** around
  the cover, the progress bar's **Pulse**, or a **Glow** that breathes with the music; in your light, your accent, white
  or the cover's color. It listens to Prism's own sound (no microphone, no permission) and only runs while the player is
  open and playing, so turned off it costs nothing; Reduce motion keeps it still.
- **Your buttons:** pick three to six for the row under the controls, in your order: lyrics, video, up next, play on,
  sleep timer, like, not for me, add to playlist, share, Sing, DJ mode, equalizer, download, start radio, and **speed**
  (songs at 0.75× to 1.5× for this session; crossfades blend at the songs' own speed, then it carries on). With Like in
  the row, the heart beside the title steps aside.
- **Notification and car buttons:** choose and order the buttons beside play. Your phone's notification and lock screen
  show the first two, Android Auto shows them all; Back 10 s and Ahead 30 s are new choices.
- **Your order:** the current lyric, progress bar, controls, volume and buttons, top to bottom as you like.
- **A waveform progress bar**, drawn from each song's own loudness (from Prism's song scan; nothing extra downloaded),
  and the progress bar in your light, your accent, white or the cover's color, thinner or thicker, with a dot, a line,
  a ring or no knob at the playhead.
- **The title** left or centred, small, medium or large, scrolling or not; and which times show under the bar.
- **The background:** the color mesh can drift slowly (it stays still unless you ask), its colors softer or richer, the
  blur softer or clearer; an animated cover can fill the whole player; and "Your background" can be any of Prism's
  backgrounds or a photo of your own just for the player, not only Home's.
- **Gestures:** what swiping left, right and up, a double tap and a long press do on the cover, and swipes on the mini
  player: next or previous song, skip back or ahead, play / pause, like, up next, lyrics, sleep timer, video, Sing, the
  song's menu, stop, or nothing.
- **Vinyl**, a new Look built around the record player.
- **Drag to reorder, everywhere you put things in order:** the player's buttons, the notification and car buttons, the
  order under the title, the tabs, lyric sources, Library's tiles, Local files' sections, Customize Home and your
  playlists. Hold a row (or drag its grip) and it lifts onto a card edged in your light; the others slide out of its
  way, and holding it near the top or bottom of the screen scrolls the page, so the last one can go first in one move.
  The up and down arrows are still there.

### Fixes
- **Prism closed by itself on some Android 12+ phones** (#100's log) when a download started or restarted while it was
  in the background (Wi-Fi coming back, a stalled download restarted). Downloads now wait for Prism to be opened in
  that case; ones already running carry on.
- **Shuffle and repeat come back after Prism restarts** (#101): the queue did, but shuffle was off again. Now shuffle
  returns in the same order, and repeat as it was.
- **A live version no longer plays as the studio song** (#113): with "Songs, not videos", Prism only swaps a video for a
  song when it's the same recording: anything in brackets that isn't just "Official Video", "Lyric Video" and the like
  (a live album, a session, another edition) tells them apart, and the lengths must match.
- **The font picker lines up** (#117): an even grid across the page, every card in a row the same height, also with
  larger text.
- **The browser remote's queue lines up** (#112): the now-playing card's style was spilling onto the queue's song.
- **"Last crash" says when the phone closed Prism** (#103): on Android 11 and later, Prism asks Android why it ended
  last time (not responding, out of memory while playing, a crash in a library) and saves that as a crash too, with
  Android's own trace, so the diagnostics no longer show an older crash instead.
- **Diagnostics say more about refused streams** (#114): which way the song was asked for, and for a refused part of a
  song, how far in it was, which of YouTube's apps the link came from and whether it carried a PO token.
- The speed you picked no longer stays at 1× after a crossfade (it came back only when the blend happened to change the
  song's speed).
- **Android 8 with Bluetooth headphones:** opening the player could close Prism (it asked for the device's address,
  which Android only has from 9 on).
- **Speed dial** has even margins on both sides (#67): the next page no longer peeks in at the right; the dots say
  there's more.
- The Smart Shuffle button on the player follows the queue when it changes while the player is open.

## 1.7.3

The second customization update: Looks.

### Looks
- **Save your look.** Settings → Appearance → Looks keeps your colors, type, icons, backgrounds, and the player's and
  lyrics' look together as a Look. Switch between them with one tap; everything changes at once.
- **Prism's own Looks**: Prism (how it comes), Paper, Midnight, Neon, Soft and Studio. Copy one to make it your own.
- **Share a Look** as a QR code, a code in a message, or a `.prismlook` file that can carry its background photos and
  (if you tick it) your font. A Look someone sends shows first, with Use it and Save; it can only change how Prism looks.
- **Community Looks.** Looks → From the community lists Looks people added to Prism's GitHub repository (`themes/`, by
  pull request; its README says how). The list is downloaded only when you open it, and each Look shows as a preview
  first. Four to start: Aurora Glass, Graphite, Sunset Drive and Forest Walk.
- **Day and night.** One Look by day and another by night, switched at sunrise and sunset (worked out from your time
  zone, no location needed) or at times you set.
- **Undo.** After every change to the look, an Undo bar says what changed ("Corners → Sharp"). Looks → History goes
  back further (the last 10 changes).
- **Nothing gets lost.** Switching to a Look while your own mix isn't saved keeps it as a "Before <Look>" card, so you
  can always go back to it. Each Look's menu (or a long press on its card) says what it changes, and so does a Look
  someone sends you, before you use it.
- **Update your Looks.** Change something after putting on one of your own Looks and the Looks page says what you've
  changed since you saved it, with **Update** (or Save as new, or go back to it as saved). Any of your Looks can also be
  updated to your settings from its menu. Undo takes an update back.

### Changing the look
- **A live preview** at the top of every look page: Home with the mini player and tab bar, the player and the lyrics,
  as your choices make them. It stays pinned under the bar as you scroll.
- **Customize by tapping.** Turn it on in Appearance (or hold the Settings tab), then tap the mini player, the tab bar,
  the player or any Home section to change just that, right there, with an Undo in the sheet. The player's ⋯ →
  Player settings opens the same sheet over the player.
- **Reset this page** on each look page, beside Appearance's Reset look.

### Fixes and smaller changes
- **Prism closed on its own on some phones (#103).** Reading a long Liked songs list could fail on a phone short of
  memory, and that closed the app. Now the list is simply read again.
- **Liked songs follow YouTube Music's order (#99).** "Original order" now matches YouTube Music after every sync; a song
  you liked again elsewhere used to keep its old place.
- **Songs not available in your country (#93)** now play their music video's sound instead of stopping, when the video
  is allowed where you are.
- **Your own uploads (#101)** play as themselves. YouTube Music marks them apart from its own songs, and Prism took them
  for videos, so "Songs, not videos" swapped an upload for YouTube's own recording of the same name (a 6-minute full
  version played as the short one). They're songs now, are asked for with your account from the start, and never get
  a music video looked up.
- **Lyrics timing (#109)** goes up to two minutes either way, with ±5 s steps. It's kept on your phone, for that song.
- **Section headings (#104, #107)** are a little bolder, the same on every page, and the whole heading row opens the
  section, with its arrow at the far end where your thumb reaches it.
- **The time-of-day mix is named after what's in it.** "Your Friday morning indie" could be mostly rock: it was named
  from its first songs' genres before Prism had settled them. Now a genre is in the name only when more than half the
  mix is that genre (else "… mix"), and the name is checked again as genres become known.
- **Android Auto pictures.** Downloads showed a warning sign (it had no picture: now it has its own, as does Liked songs
  without one of yours), YouTube Music's mixes and "Liked Music" on Home were blank tiles (their covers come as PNGs;
  the car now always gets JPEGs, from the covers already saved on your phone first), and a cover that can't be fetched
  shows a playlist tile instead of nothing.

## 1.7.2

The first of the 1.7.x customization updates: color.

### Color
- **Pick any color.** A new color picker is used everywhere Prism asks for a color. You can:
  - pick on a color wheel, or set exact RGB and HSL values;
  - type or paste a color code (#3A86FF);
  - take a color from what's playing, your wallpaper or any spot on a photo;
  - star colors to keep them; your recent colors are kept too.

  Before you use a color, it shows how readable it is on dark and light pages.
- **Your light.** The light that runs through Prism (progress, the Play ring, switches, the song that's playing) comes in 12 presets: Prism, Sunset, Ocean, Aurora, Forest, Ember, Candy, Neon, Ice, Gold, Silver and a color-blind-safe one. There's also **White** (**Black** in light mode), and you can build your own from 2 to 7 colors, smooth or as hard bands like the logo's bars. White light is now this White preset, with its light really white (or black).
- **Your accent.** The accent comes from the song's cover, your wallpaper or a color you pick. It can be used **exactly as picked** or softened to suit dark and light pages, and light mode can have an accent of its own. With the song's cover, you choose which of its colors to use, whether grey covers still give a color, and how the color moves between songs.
- **Your accent on every page.** Artist, album and playlist pages (and the menus opened from them) now use your accent, like the rest of Prism. Before, they always took a color from their picture, so the accent you picked hardly showed. Their frosted backgrounds still take the picture's colors. To have them take their picture's color instead, set Color → Artist & album pages → **Picture's color** (black-and-white pictures keep your accent). An artist's page can also have a color of its own (⋯ → Page color).
- **Page background.** Automatic, plain grey, tinted with your accent, or a color of your own, with a strength slider. It colors the frosted background behind Home, Search, Library, Replay and Settings, and sheets and dialogs.
- **Text contrast.** Standard, Medium or High, for firmer secondary text, lines and accent.
- **A Color page you can see.** A preview of your Prism (a small album page on your real background, in dark or light mode) stays pinned at the top while you scroll, with the three parts numbered: ① the light, ② the accent and ③ the background. Every choice is a card that shows what it looks like, each group lists everything it changes in the app, the color picker shows Prism with the color as you drag, and "Reset colors" at the bottom puts everything back.
- **Appearance is tidier.** It now holds the theme and four pages: Color, Text, Icons & shapes, and Glass & backgrounds.
- **"Color" is spelled the US way** throughout Prism.

### New
- **App icon shortcuts.** Press and hold Prism's icon for Search, Recognize a song, Liked songs and Downloads.
- **Song credits.** A song's menu (and the player's ⋯) shows who performed, wrote and produced it.
- **Library your way.**
  - Pick and order the tiles at the top of Library ("Edit tiles", or Settings → Library & Replay → Library tiles).
  - Put your playlists above the tiles (Edit tiles → "Where your playlists go" → Playlists first).
  - Albums can be sorted with downloaded ones first.
- **Pin Prism playlists to Home.** Customize Home → Pinned playlists lists your Prism playlists as well as YouTube Music's.
- **Square video covers** (#92). A video played as a song no longer shows YouTube's black bars in its cover: the bars are trimmed and the middle of the picture is used, on the player, in lists, in the notification and in Android Auto. Turn it off under Settings → Player → Cover.
- **Lyrics on mobile data** (#95). Turn it off under Settings → Lyrics → Sources, and on mobile data Prism looks lyrics up only once you open them. Lyrics already saved on the phone still show.
- **Which downloads failed, and why** (#98). Under "N downloads failed", **See which** lists each song with the reason (YouTube refusing the link, the phone going offline or running out of space…), and you can try each one again or remove it.
- **Downloads.** The songs' search, genre filter and sort also apply to the downloaded albums and playlists above them.
- **New releases.** Filter by time (this week, this month, 3 months) and type (albums, or singles and EPs), hide what's already in your library, and sort by newest, artist or title.
- **Release Radar** also watches the artists you follow on YouTube Music.
- **Save from a long press.** Holding an album or playlist has a button to add it to (or remove it from) your library; holding an artist has a star to favourite them.
- **Likes as stars.** Settings → Appearance → Icons & shapes → "Likes as stars".
- **Immersive mode** can keep previous, play and next on screen ("Keep controls on screen").
- **Casting is easier to find.** The line under the player shows the cast mark, and the player's ⋯ has "Play on a speaker or TV".
- **Premium quality.** Signed in with YouTube Music Premium, Playback & sound → "Premium quality" plays Premium's 256 kbps streams. Without Premium, Prism notices and plays as usual.
- **The Ring Replay card** says "Your top songs, artists and genres" like the other cards, and shows how far through the year it is to a tenth of a percent.
- **Pure black covers every background.** In dark mode with Pure black on, every screen background (the color ones, Simple patterns, Designs, Now playing) sits on true black, its colors kept but quieter.
- **Android Auto.**
  - Four tabs along the top: Home, Made for you, Recents and Library, with Liked songs, Downloads and your Prism playlists inside Library.
  - With "Covers in a grid" (on by default; Settings → Lyrics → Android Auto), Home shows its sections as rows of covers, and Library and Made for you show as covers.
  - YouTube Music's own mixes in your library (My Supermix, Discover mix…) show their covers.
- **Crash details stay on your phone.** If Prism closes unexpectedly, Settings → About → Diagnostics shows "Last crash", and you can share it.

### Fixed
- **Crossfade not happening at all on some phones.** When the second player couldn't line up after two tries, the blend was dropped. A plain blend now goes ahead anyway, crossed over slowly, and every reason a song isn't blended is written to Playback diagnostics.
- **A hiccup a few seconds into songs.** When a song's measured loudness came in, its volume jumped several dB at once. Changes now ease in over two seconds, and small ones mid-song are skipped.
- **DJ mode was laggy.** It reads one song's key at a time while music plays, and its new order goes into the queue at once (at most every second and a half) instead of one move per song.
- **Stutters on slower phones.** Background song scans (loudness, tempo, keys, music-video timing) step aside when the sound runs short.
- **Freezes when Android's audio service is slow** (after a restart, during a Bluetooth switch, with some phone makers' sound engines): the equalizer and sound effects are now set up on a thread of their own.
- **Starting Prism** does less before the first screen appears.
- **Typing in Search could lag** with a big library (#91).
- **Your own uploaded songs** that YouTube refuses twice are asked for with your account (#101).
- **Pure black stayed grey** behind the frosted background in dark mode.
- **A white accent drew a grey progress bar** on the player and mini player.
- **Album and playlist pages looked black below the picture.** They keep more of the picture's color again, and text still reads.
- **Page background did almost nothing** behind the Ambient background, and **Accent tint with Wallpaper** used your old picked color instead of the wallpaper's.
- **Secondary text turned pink** with a strong page tint, so it looked like a faded accent.
- **"Ready offline"** now says it's a mix of songs already on your phone.
- With Reduce motion on, the playing bars no longer animate behind the scenes.

## 1.7.1

### Fixed
- **Crossfade could go silent, or not happen at all.** Prism plays a song's ending on a second player while the next song fades in, and on a slower connection that second player could stop to load. Just before a blend, that put it out of step, and the crossfade was dropped. Mid-blend, the fade stood still with both songs nearly silent until the ending came back, then rushed through. The ending is now loaded whole before it's needed. A blend that isn't in step is put off by two seconds and lined up again instead of dropped. If the ending still stops to load, the next song simply comes up. Each blend is now logged step by step in Playback diagnostics.

## 1.7.0

### New
- **Search on the Podcasts page**: type a show, host or topic at the top of Podcasts and shows and episodes appear as you type, without leaving the page. (Search's own Podcasts and Episodes filters still work too.)
- **Genres you can trust**: a song's genre now comes from Apple's catalogue (iTunes) where it can: each song's own, so a rapper's country single is Country, a musical's songs are Soundtracks & musicals, and metal, blues and Christian music are named as such (Deezer, used before, files them under Rock and Pop). One request covers an artist, about 20 a minute as iTunes allows, worked through in the background and kept; Deezer answers at once until then. Songs iTunes doesn't list for their artist are checked against their own release on Deezer. On a test set of 68 well-known songs across every genre, 66 now get the right one (54 before).
- **Prism pictures for your account**: twelve profile pictures drawn in Prism's split light (a prism, a spectrum ring, the mark, an aurora, a dawn, a night sky and more), beside your own photos and your initial, on your Prism Account's page.
- **A Prism Account**: who you are in Prism, a name and a picture, kept on this phone and in your backups, with no email, password or server. Prism greets you by it on Home and uses it wherever it names you (Blend, Listen Together, handoff, the player's "Sam's Phone"). It owns everything Prism keeps on the phone: likes, Prism playlists, saved albums, starred artists and Replay. Making one changes nothing you already have. Signed in to YouTube Music too? Your account's page can copy its playlists, saved albums and artists into Prism, so they stay on the phone after you sign out (copy again to bring them up to date), and signing out offers to keep a copy first. New installs are offered one first on the welcome screen; everyone else finds it at the top of Settings.
- **Prism Music**: the music Prism finds for you is called Prism Music in Prism now: in Search, Settings, Customize Home, autoplay, lyrics sources and messages. Where it's your Google account that's meant (signing in, playlists saved to your account, Premium), it still says YouTube Music.
- **Blend covers**: each Blend gets a cover of its own, your top song and your friend's in two rings of split light that cross where your tastes meet, with the match in the corner. Or pick any photo for it (the Blend's ⋯ → Pick a cover, or tap the cover), only for this phone. Blends are no longer in Home's Made for you, which is Prism's own mixes; they're in Library → Blend.
- **Best part is in color again**: the Best part tag is lit in the split light, and its mark on the scrubber is a fainter, shorter hairline.
- **The Replay card heads Home**: it's the first thing on Home now, on new installs and (once) on Home layouts customized before 1.7; move it in Customize Home if you'd rather have it elsewhere.
- **Pick how Replay looks on Home**: Settings → Home screen → **Replay card** has nine looks, from the quietest to the most colorful, with a preview of your own year: **Quiet** (glass and a hairline of split light), **Ledger** (time, top artist and top song side by side), **Wash** (a faint wash of color and your months as slim bars; the default), **Spectrum frame** (the card ringed in split light, the time in color), **Ring** (how far through the year you are), **Cover** (your top song's cover), **Prism stripe**, **Spectrum bars** (your months, red to violet) and **Aurora** (full color). The **Prism** profile picture is redrawn too: white light falling into a glass prism and opening out as a fan of color.
- **Prism's own radio**: when your queue ends, autoplay can now pick from your own music instead of only asking YouTube: songs you've played together before, the same artists and genres, keys that blend when DJ mode has read them, and what you play most, with no artist twice in a row and nothing you've just heard. Settings → Playback & sound → **Autoplay from**: **Both** (the default, your music mixed with YouTube's radio), **Your music**, or **YouTube Music**. Offline, or after a music file from your phone (which used to stop autoplay), it's always your music. Made on the phone from Replay's history; nothing is sent anywhere.
- **More of Home made on your phone**: **On repeat** (your most played this month), more mixes in **Made for you** (a mix for each of your top genres and artists, from songs you know, and **Ready offline**: what plays with no connection, after the Daily Mixes), plus **Repeat rewind** (songs you had on repeat months ago) and **Forgotten favourites** (likes and old favourites you haven't played in a while), which you can add from Customize Home. Another upload of the same song doesn't count twice.
- **Home works offline**: with no connection, Home says so and shows only what plays: Quick picks from your downloads, songs kept whole and your files, and Made for you, your mixes, Recently played and Liked songs cut down to what's on the phone. YouTube's shelves come back by themselves when the connection does.
- **Blend on both phones at once**: on the same Wi-Fi, when a friend scans your Blend code, their Prism sends theirs straight back to yours, so you both have the same Blend without swapping codes. No server: the phones talk to each other directly, only while your code is on screen, and only a phone that read the one-time key in your code gets in. On different networks (or with a code sent as a message) it works as before: they send theirs back.
- **A tour of Prism**: after setting up (and the first time anyone opens 1.7), a short tour walks through Home, the player, lyrics, Sing, DJ mode, Made for you, Search and Recognize, your library, Replay and Rewind, Prism Connect and how to make Prism yours, a page at a time. Skip it whenever you like, and take it again from Settings → About → **Take the tour**. From now on, an update with new features shows only what's new.
- **Playlists and saved albums without an account**: make playlists in Prism (Library → New playlist, or any song's menu → Add to playlist) and save albums with the bookmark on their page, all kept on this phone and in your backups, no YouTube Music account needed. Prism playlists can hold music files from your phone too. Signed in, they sit beside your YouTube Music playlists.
- **Podcast episodes have a podcast player**: back 15 seconds and ahead 30 instead of previous and next, and the speed (1×, 1.25×…) a tap away where Lyrics and Video were.
- **The sleep timer shows on the player**: a small moon with the time left beside the device line; tap it to change or cancel the timer.
- **Music files without artwork get a cover**: the song's initial on a color of its own, edged in the split light, instead of a bare note.
- **Notifications are asked for when they're needed**: Prism no longer asks before its welcome screen, with no reason given. The tour's last page explains what they're for, or the first download asks.
- **A new look, "Aura × Spectrum"**: Prism is redrawn from the ground up. Pages take their color from what's playing, under frosted glass, with soft corners, and the tab bar and mini player float on glass. The split light of Prism's name runs through it: the song that's playing, progress lines, the tab and chip you're on, the ring around Play, a "Continue …" line on Home for the album you were in, Liked songs' cover, switches when they're on, and Settings' sections from red to violet. The new font is Outfit, light at large sizes. Every icon is new, drawn for Prism, as are the app icon (all six launcher icons) and the notification icon. In synced lyrics the line being sung thickens word by word as it's sung, with a thin line under it showing how far through it you are. Prism's own home-screen widgets (Prism, Cover, Disc, Strip, Recognize) match it. There's a new **Spectrum** screen background, and the color-from-the-cover background is now called **Ambient**. Prefer a quieter, neutral Prism? Settings → Appearance → Color → Style → **White light** (the light before the prism splits it): neutral greys and ink, frosted backgrounds in grey, and your accent color where the split light would be (progress, the Play ring, what's playing, switches), as in Prism's earlier look. Pick the white accent for no color at all; color then stays only in the album covers and photos. Every look setting you had (corners, glass, backgrounds, tab bar and mini player styles, the Cupertino icon pack) still works.
- **New fonts** (Settings → Appearance → Text → Font): besides Prism's own (Outfit), **Clean** (Manrope), **Geometric** (Plus Jakarta Sans), **Airy** (Urbanist), **Rounded** (Nunito), **Editorial** (Fraunces, a modern serif) and **Mono** (Geist Mono) now come with Prism, so they look the same on every phone (Google Sans and Rounded used to need a Pixel's own fonts). Each works for the lyrics too, including the line that thickens word by word as it's sung. Google Sans and Condensed are gone; if you had one picked, you're moved to Clean. System and your own font file are still there.
- **Your own best part**: hold the **Best part** button (or ⋯ → **Your best part**) to pick where a song's best part starts: on a bar, nudged by 5 seconds, from where the song is now, heard before you save it. It's what Best part, "Play from the best part", Rewind and Blend use for that song from then on, and it can go back to the part people replay most. The button itself is quieter now.
- **Android Auto: Play as a DJ set**: every song list in the car has **Play as a DJ set** under Shuffle play: the list in the order that blends best, mixed and beat-matched (DJ mode).
- **Hide Home's Continue line**: Settings → Home screen → **Continue line** turns off the "Continue …" line under the greeting.
- **Settings, tidied**: every setting reads in one short line, with the full explanation one tap away (ⓘ). The Player page is shorter: what shows on the player is one set of chips to tick, and seeking and haptics are one Gestures group. Lyrics settings are split into Look and Language. The player's ⋯ menu has **Player settings**.
- **Sing, as on Apple Music**: with the lyrics open, their foot is a glass bar with where they come from (tap to change) and a **Sing** pill. Tap it and Sing goes on: its controls (how much of the voice stays in, the key, how far the song is ready) come up just above, with the lyrics playing on behind them; tap it again for off, hold it to hide or show the controls. From the player's ⋯ menu, Sing opens the lyrics the same way. **Get songs ready while Sing is off** (Settings → Lyrics → Sing) separates the playing and next songs ahead, so Sing starts at once when you turn it on.
- **Immersive mode, redrawn**: the lyrics are set light, each word thickening as it's sung, with a thin line of split light under the line showing how far through it you are. A tap brings up the song's name and a glass capsule with previous, play / pause and next as well as its settings (still held to leave), and a hairline of split light along the bottom shows how far through the song you are.
- **Play on**: the player's device list is the frosted sheet the player's other menus are: this phone, speakers and TVs, and Prism devices in glass groups, with what's playing on a disc ringed in split light. While casting, the line under the controls is a glass pill with the cast mark.
- **Browser remote, redrawn**: Prism's own type (Outfit, served by the phone), glass panels over the cover's color, the spectrum ring round Play and in the progress and volume bars, and lyrics set light that thicken word by word as they're sung, with a line under the one being sung.
- **Tablets, foldables and landscape**: on a wide screen the tabs move to a rail down the side, and the player shows the cover and controls beside the lyrics, the queue or the stream stats. On its side a phone gets the same player with a smaller cover. A foldable half-open like a laptop puts the cover above the fold and the controls below it. Settings lists its pages beside the one that's open, and albums and playlists keep their cover beside the songs on a tablet in landscape.
- **Jump to the best part**: in a song's first seconds a **Best part** button under the progress bar skips to the stretch listeners replay most (YouTube's own "Most replayed" data). Without that, the best part is where the chorus first comes round in the lyrics. The start lands on a lyric line or a bar, not mid-word. A song's menu has **Play from the best part** too. The button can be turned off in Settings → Player.
- **Headphone correction** (Settings → Playback & sound → Equalizer): pick your headphones from almost 9,000 measured by the AutoEq project, and Prism corrects them to sound neutral (the Harman target). The correction's curve is shown, with a strength slider. Each pair of headphones keeps its own correction and switches on when they connect; the phone's speaker and the car never get one. You can also import a ParametricEQ / EqualizerAPO file. It runs inside Prism, so it works on any phone and with Crossfade, and stats for nerds shows it.
- **Romanized lyrics**: songs in Korean, Japanese, Chinese, Arabic, Hindi, Russian, Greek and other scripts get each line in Latin letters under it (or in its place), so you can sing along. Karaoke lyrics light up word by word in Latin letters too. Korean (Revised Romanization, with its sound changes), Japanese, Russian and Greek are spelled out on the phone. Japanese kanji need a one-time 13 MB download of reading data ("kimi no na wa", not "kiminonaha"). Chinese (pinyin), Hindi, Arabic and the rest come from Google Translate, which is sent only the lyrics' text. Results are kept, so they work offline after the first time. Lyrics in your phone's own language are left as they are unless you ask (Settings → Lyrics → Romanize lyrics), and the player's ⋯ menu turns it on or off for one song.
- **Sing**: karaoke with the song's own music. The microphone button by the lyrics (or the player's ⋯ menu) takes the singer's voice out, worked out on your phone from the very copy that's playing (Deezer's Spleeter, a one-time ~50 MB download). A song is ready to sing a few seconds after you start, and a whole one takes about 30–40 s on a recent phone. The next song is prepared while this one plays. Leave a little of the lead voice in to sing along with, or none, and move the key up or down by up to six semitones (the tempo stays). The voice is only taken out where Prism has checked it lines up with the music to the sample, so nothing ever sounds doubled. Crossfade pauses while Sing is on. Prepared songs are kept (Settings → Lyrics → Sing: on mobile data or not, how much space to use, clear them). Separation is very good, but not perfect: echoes and some backing vocals can stay faintly.
- **DJ mode**: turn on **DJ** in the queue (or pick **Play as a DJ set** on an album or playlist), and Prism plays the songs ahead like a DJ would. Each song's key is found on your phone. Prism then orders the next 40 songs so each one goes into the next in a key that blends (the Camelot wheel), at a tempo close enough to beat-match, with no artist twice running. They're crossfaded and beat-matched: long blends where the keys sit together, short ones where they don't. Pick the energy: **Steady**, **Build up** or **Wind down**. The queue shows each song's key and tempo ("8A · 124"). Each song gets a quick listen (three 25-second stretches of it, a few songs at once, the next ones first), and the order improves as each one comes in; the playing song and the next one never move. Once a day, on Wi-Fi while your phone charges, the songs you play most and like are looked at ahead of time, so most sets start in order. On mobile data the queue says the songs are waiting for Wi-Fi, with a button to use mobile data instead. On 28 well-known songs the key was exact for 18 and within one step on the wheel for 26. Unclear keys aren't guessed. Shuffle turns DJ mode off, and a new queue starts without it.
- **Made for you**, worked out on your phone from what you play (nothing about your listening leaves it):
  - **Daily Mixes** (up to six) group your artists by what goes together, by genre and by which ones you play back to back. Each is 50 songs: ones you know, with new ones like them in between (two in every five). They're made once a day and stay the same all day, offline too.
  - **A time-of-day mix** ("Your Sunday evening rock") is made from what you play at this time of day, plus more like it.
  - **Release Radar** keeps an eye on the artists you've starred, saved and play most (looking every few days). It finds their new albums, singles and EPs with their exact release dates (from Deezer). They show on a **New releases** page and on Home, and in a weekly **Release Radar** playlist (refreshed on Fridays). There's a notification when a starred artist puts something out; turn it off in Settings → Home screen. The first look at an artist only learns what's already out, so their back catalogue is never announced as new.
  - The mixes are on Home ("Made for you", added once to existing layouts) and in Android Auto.
- **Smart Shuffle**: press shuffle again on a playlist or album, and a recommendation like the music around it slips in after every three or four songs (marked ✦ in the queue). While one plays, the ✦ button by the like button adds it to the playlist (your own YouTube playlists) or turns it down. Turning Smart Shuffle off takes the ones still to come back out.
- **Exclusions**: "Don't play this artist" (artist page ⋯) keeps an artist out of radios, autoplay, Smart Shuffle, your mixes and Smart Downloads. "Leave out of my taste" (a playlist's ⋯, a song's menu) still plays them, but they no longer shape your recommendations, search, or Smart Downloads; Replay counts them as usual. Everything left out is listed in Settings → Library & Replay → Exclusions.
- **Blend** (Library → Blend): a playlist of two tastes. Show a friend your QR code (they scan it in Prism), or send it as a message they share to Prism, or paste it. Prism shows your taste match, the artists and genres you share, and a 50-song playlist: the songs you both play first, then each of yours in turns. Both phones make exactly the same Blend once you've each taken the other's code. There's also "Discover together" (new to you both, like what you share), Play / Shuffle, Save to YouTube Music, and a picture to share. No server is involved: the code carries your Blend name, top artists and songs, and genres, and you can see exactly what's in it ("What you share"). Artists you don't play and things left out of your taste aren't in it.
- **Song recognition**: tap the new button in Search's box (or add the **Recognize song** tile to Quick Settings) and Prism listens for up to 12 seconds to tell you what's playing around you. It then finds the song on YouTube Music, ready to play, like, or open its album or lyrics. The sound stays on your phone: Prism makes Shazam's fingerprint of it on the phone (ported from SongRec) and only that goes to Shazam, with no location. If Prism is playing, it pauses while listening (or keep it playing to recognize Prism's own song). Offline, the fingerprint is kept and looked up when you're back online, with a notification when it's found. Everything found is in Library → Recognized. The microphone is asked for the first time you use it, and used only while that screen is open.
- **Prism Connect** (Settings → Prism Connect):
  - **Speakers & TVs**: the player's device list (the line under the controls) now finds Google Cast devices (Chromecast, Google TV, speakers and TVs with Cast built in) and speakers, TVs and AV receivers that play music sent to them (DLNA / UPnP) on your Wi-Fi. Pick one and the music plays there; the queue, lyrics, notification and Android Auto carry on as usual, the phone's volume keys and the player's volume bar set its volume, and pausing or skipping on the TV's own remote comes back to Prism. Songs go to it decoded on your phone into plain uncompressed audio, which every renderer plays (a renderer that refuses one kind gets the other), and run into each other without a gap where it allows. The equalizer, Crossfade, Sing and videos are the phone's own, so they're off while casting, and live streams play on the phone. Google Cast devices play in Google's own media player on the TV, with the song's title, artist and cover; Prism talks to them directly over the open Cast protocol, with no Google SDK, so nothing else is installed. Leaving Prism's player on the TV (its Back button) or opening another app there ends casting; the music pauses on the phone.
  - **Control from a browser**: switch it on and open the address shown on any computer or tablet on the same Wi-Fi. After typing a six-digit code from the phone (once per browser), it shows what's playing, its controls and volume, the queue, search, and lyrics that light up word by word. Only browsers you pair get in, guessing the code is locked out after a few tries, and nothing goes through the internet. It works while Prism is open or playing.
  - **Handoff**: move what's playing to another phone or tablet with Prism (the same Wi-Fi, "Available for handoff" on there), or bring theirs over: the whole queue, the song and place, shuffle and repeat. The first time, the other device shows a code to type in; after that they know each other. Songs that are files on one device stay behind. Prism has to be open (or already playing) on the device the music moves to, as Android doesn't let an app start playing from the background; if it isn't, you're told so and the music keeps playing where it was.
- **Rewind**, every year: Replay now has a chip for each year you've listened in (not only this year and last), and each year's story is its **Rewind**. This year's opens on December 1 (with a notification, which you can turn off in Settings → Library & Replay, and a Rewind card on Home through December); earlier years any time. Besides your minutes, genres, top artists and songs, habits, discoveries and personality, it has your year season by season, your top album, your biggest month, the first song you played that year, your year in Prism (songs sung, DJ sets, Smart Shuffle finds, songs recognized, Blends, new releases found) and how the year compares with the one before. Your #1 song shows a line from its best part, and every song in the story plays from its best part. A quiet year gets a short story rather than empty slides. Any slide can be shared as a picture, and "Save Top 100" makes a YouTube Music playlist of the year. Once a year is over, its Rewind is kept as it was (in your backups too), so it opens at once.
- **Playback diagnostics** (Settings → About): if music stutters, switch this on and Prism keeps a log on your phone of what the audio does: the output being set up, moments it ran dry, Bluetooth and other devices coming and going, how long Prism's own sound processing takes, and the background work running beside the music. Tap **Mark a stutter** in the player's ⋯ menu when you hear one, then **Share diagnostics** to send the log (with your phone's model and Prism's audio settings) to whoever is helping. Nothing is sent unless you share it.
- **Recognize widget** (Prism · Recognize in your launcher's widget list, or Library → Recognized → Add the widget): a round button on your home screen that opens a small listening card over whatever's on screen, without opening Prism. Made wider it also shows the last song recognized with a Play button (one row at 4×1, a card at 4×2), or that one is saved to look up once you're online. The **Recognize song** Quick Settings tile now opens the same card instead of the whole app. The microphone is only used while the card is on screen; leaving it stops listening.
- **Pick artists you like**: after the welcome screen (and any time from Settings → Home screen → Your artists), choose from YouTube Music's genres or search. They're starred on your phone (nothing is followed on YouTube), so your mixes and Release Radar have something to go on from the first day.
- **Quick picks made around your artists**: once you've a little history, Quick picks are favourites by the artists you play that you haven't heard for a few days, new songs from radios of your top three artists, and YouTube Music's own picks when they're by artists you play; no artist twice in a row. They're made once a session (pull down on Home for new ones).
- **Mini player**: a **Previous** button to go with Next (Settings → Layout & navigation → Mini player), and the **Fill** progress now fills in the split light (or White light's color).
- **Lyrics**: Settings → Lyrics → **Progress line** turns off the thin line of split light under the line being sung (on the lyrics page and in immersive mode).
- **Discord**: your status shows the song's cover (Discord takes it in through Prism's own Discord application).
- **Android Auto** shows the app icon you picked (Settings → Appearance → App icon), from the next time playback starts.
- **Songs start sooner**: Prism asks for a song's stream in one request where it can (falling back to the old way), resolves the song after next as well as the next one, and keeps background lookups out of the way while a song is loading. On Wi-Fi a song started in about 0.7 s instead of 2.5 s, and skips took about 0.2 s. The whole song is now buffered, not the next 30-60 seconds, so a dropped signal doesn't stop it.
- **Double-tap the cover's sides to seek** back or ahead (3, 5, 10 or 15 seconds; Settings → Player → Gestures) (#79).
- **Tap the time left** under the progress bar to show the song's length instead (#82).
- **Pull the mini player down** to stop playing and clear the queue (#59).
- **The queue shows what played**: the last 100 songs sit above Now playing, and the queue opens at the song playing (#68).
- **Search in Settings**: the search box on Settings' first page finds any setting and opens its page at it (#73).
- **Songs, not videos → Everywhere** (Settings → Playback & sound): a video you tap, or one coming up in the queue, plays as its song, with the video a tap away (#80).
- **Search can open the keyboard** as soon as it opens (Settings → Layout & navigation → Search) (#71).
- **Search lyrics with another title**: from the Lyrics source sheet, look a song's lyrics up under a title of your own; it's remembered for that song (#63).
- **Background vocals** show smaller under the line, lit word by word, and two karaoke lines sung at once are both lit (#65, #66).
- **Artists a song names without a link** (featured artists, often) now open, from the player and from the song's menu (#78).
- **Downloads** shows albums and playlists you've downloaded above the songs, and its "Recently added" is newest first (with "Oldest first" too) (#77).
- **Sort orders are kept**: Liked songs, Downloads, each playlist and Library's tabs remember how you last sorted them.

### Fixed
- **From GitHub issues:** removing a song from a playlist could fail with "Precondition check failed"; it now tries again with fresh details (#87). The player's cover was blurry (it was decoded at a small size), and downloads now save full-size covers (#86). An artist's "From your library" listed only the first songs, under an empty heading (#69). Speed dial's last page showed a sliver of the previous one (#67). Songs with non-Latin titles could get another song's animated cover (#58). The Discord sign-in page was blank (#72).
- **From Reddit:** Local files could spin forever when music access was given outside the page; it now checks again when you come back, and lists songs before their artwork is ready. Lyrics are faster: if a source is slow, the next one is asked too after a moment (its answer still only used in your order).
- **Tidier settings and looks**: Appearance only offers an accent color with White light (Spectrum's color is the split light), and only shows the color swatches for your own color (Now playing and Wallpaper pick theirs). The Best part tag's flame is plain white. The song title in the player is a touch smaller. Customize Home calls YouTube Music's shelves YouTube Music again. "Japanese reading data" is now "Japanese kanji readings", which says what it's for: only Japanese needs a download to spell its lyrics in Latin letters. The Quiet, Prism stripe and Aurora Replay cards read "Your top songs, artists and genres", and the Ring card follows the year by the minute (it could sit on a stale percentage while Prism stayed open). The Prism app icon's mark sits a little higher, so it looks centred in a circle.
- **Music videos are the artist's own**: some songs opened a fan's lyric video, a translated re-upload or a different song ("What You Need" by Bring Me The Horizon, "Memories" by Panic! At The Disco, "Bohemian Rhapsody"). A video now has to come from the artist's channel, their VEVO or their record label, and its title has to be the song's title, not just contain it; otherwise the song simply has no video. If fan uploads crowd out the first page, Prism asks once more for the official video.
- **Replay counts a song once**: two uploads of the same song (an album's and a single's) were listed separately in Replay, Rewind and the Replay card; their plays and time are added together now.
- **Shortcut tiles**: an album of music files on your phone showed as one tile per song; it's one tile now, and it plays the album.
- **Prism's radio skips clips**: tracks under a minute (intros, skits, sound files) no longer turn up in autoplay.
- **Best part**: its words are plain white again; the tag keeps its outline and flame in the split light.
- **Recognized**: an empty list now says what it's for, with a button to recognize a song.
- **Tapping the device under the player closed Prism**: the "Play on" sheet scrolled inside a sheet that scrolls, which Android refuses. It opens now.
- **Prism could stay "offline" with the connection back**: it read the phone's network a moment after Android announced it, and on some starts got nothing, then acted offline (no searches, offline Home) until the network changed again. It now takes the network Android hands over.
- **Home's section styles now work for every section**: Shortcut tiles, the Replay teaser, Your top genres, Moods & genres, Made for you and New from your artists ignored the style picked in Customize Home, Speed dial's List looked like its Grid, and YouTube's shelves fell back to a carousel in List unless they held only songs. Each now shows as Carousel, Grid, List or Spotlight, and Grid and List fit sections with only a few things in them (they left empty rows). Replay no longer says "1 minutes".
- **DJ mode read fast rock songs at half their tempo** (Mr. Brightside showed 74 BPM; it's about 148): only tempos above 152 are now taken for a song's eighth notes and halved.
- **The DJ queue's "Use mobile data"** quietly switched on "Measure songs on mobile data" for good; it now uses mobile data for that set only.
- **Translate lyrics did nothing, without saying why**, for a song already in your language: the player's ⋯ now says which language it translates into, and turning it on for such a song says so.
- **In light mode a light grey bar sat under the always-dark player** (three-button navigation): it's dark while the player is open.
- **Settings' Labels option showed for the Minimal tab bar**, which has no labels; it's hidden then. Smart Downloads says how much it uses in MB or GB (it read "using 0.0 of it"), Library no longer says "1 albums", and a podcast episode no longer says "Lyrics not available" under its title.
- **Pale covers washed the player out**: a white sleeve (Queen's *A Night at the Opera*) turned the player's background light grey, under white type. The colors from the cover now always sit dark enough for the player's text, in the cover, lyrics, queue and video views and Immersive mode.
- **A bass-boosted upload played as Bohemian Rhapsody's music video.** It named Queen and ran exactly as long as the song, which is what Prism looks for when YouTube links no video. Bass-boosted, re-tuned ("432 Hz"), reverb, looped, mashed-up and AI copies, and channels that post only those, are now never taken as a song's video.
- **Thriller's lyrics ran 45 seconds early.** KuGou had the single edit's lyrics listed under the album's length. Prism now reads the edition the lyrics name ("Single Version", "Live", "Radio Edit"…) and skips them for another edition, and lyrics already saved that way are fetched again.
- **Back skipped a page**: from an artist to a similar artist, from an album to another album, or from About to Open-source licenses, Back went past the page you came from. It now goes back to it.
- **Settings said Sing still needed downloading** after it had downloaded, if the download sheet was closed while it ran. Downloads now carry on when the sheet closes and always end up listed as on the phone. The sheet's download button is a proper button.
- **Downloads' "Use mobile data"** turned off Download on Wi-Fi only for good without saying so; it's now labelled **Turn off Wi-Fi only**.
- **Text was hard to read over some covers** on the Ambient background (and below the picture on album, artist and playlist pages): a white or yellow cover lit a dark page up behind white text, a black one darkened a light page behind dark text. The frost keeps the cover's colors but now always sits deep (dark mode) or pale (light mode), so text reads at 4.5:1 or better whatever the cover.
- **The Play button had two rings**: the spectrum ring is now its only edge.
- **YouTube Music's own mixes all showed the same cover** (My Supermix's). Prism keeps covers on the phone so they show offline, and it filed every mix's cover under one name, since their addresses differ only after the "?". Each mix now keeps its own, and the shared one is cleared on the first start.
- **Replay, Rewind, Blend and the mixes counted a song's whole credit as one artist** ("The Kid LAROI, Justin Bieber" as an artist of its own). A song now counts for its main artist. Songs are also stored with their exact artists, so a name with a comma in it ("Tyler, The Creator") stays whole.
- **Music files without an artist tag counted as an artist called "Unknown artist"**, with a photo of whoever YouTube has by that name, and their genre was guessed from the title alone (so a few test tones became "Dance & electronic"). They still play and show in Replay's songs, but no longer count as an artist or a genre. Wrong guesses already made are cleared on the first start.
- **Some songs played as AAC where YouTube Music plays Opus** ("Espresso", "Levitating", "Mr. Brightside" and others). YouTube's stated bitrate for the AAC stream is sometimes a hair higher than the Opus one, and Prism took the bigger number. Opus is the better codec at the same bitrate, so Prism now picks it first at every quality setting, as YouTube Music does (256 kbps AAC for Music Premium still beats 128 kbps Opus).
- **Some songs had no music video though they have one**, like "Linger (SiriusXM Session)" by Royel Otis, whose video is on SiriusXM's channel. Prism only took a video from the artist's own channel and never a "live" or "cover" one. A video from another channel now counts when it's the very same recording: the same length to within a few seconds, naming the artist, and from the same source the song names (SiriusXM here). Lyric videos, karaoke, fan edits and audio-only uploads still never count. A song found without a video is looked up again after an hour, not only after a restart.
- **A music video that YouTube refused to serve once fell straight back to the song.** It now gets one more try with fresh links (the other way, through NewPipe) first.
- **TalkBack:** with the full player open, TalkBack could still move to the screen, tabs and mini player hidden under it; they're now left out until the player closes. Immersive mode said nothing at all: it now reads the song and its lyrics, a double tap opens its settings, and its actions menu has Next song, Previous song, Leave immersive mode and Close the player (the swipes and the hold aren't possible with TalkBack). Its settings button is a proper button, with Leave immersive mode as an action.
- **An album or playlist page's ⋯** was a short dropdown without Play next, Add to queue or Play as a DJ set. It's now the same menu as a long press on the album, with the page's own actions (radio, library, downloads, picture, edit, delete) in a group under it.
- **Settings search** could open a setting that's hidden until another is on (Crossfade's Length needs Crossfade on). Those results now say what to turn on first, and open at that switch.
- **Library → Downloads** counted downloads waiting for Wi-Fi as "in progress"; only ones actually downloading count now.
- **A WAV file's quality** read "AUDIO/RAW 705 kbps" on the player; lossless files now show their format and resolution ("WAV · 16-bit · 44.1 kHz", "FLAC · 96 kHz").
- **Sing's download size** said 51 MB in one place and 76 MB in another; both now say how big the download is and how much room it takes on the phone.
- **Settings tidied:** Discord has its own page instead of the top of Settings; Local files has its title under Back like the other pages; the welcome screen keeps a phone's width on tablets; the album caption above the cover reads "From the album …" (it's the song's album, not necessarily what's playing); and Back leaves immersive mode for the normal player instead of closing it.
- **Search:** with little listening yet, "For you" and "Moods & moments" showed the same tiles (the second list now leaves out what the first shows); the Top result card has the same long-press menu as the results under it; and a hardware keyboard's Enter searches like the on-screen key.
- **"See all" grids of many artists' releases** (like YouTube's own New releases) said only "Album" or "Single" on each card; they now name the artist.
- **An album's "Other versions"** could show three identical "After Hours (Deluxe)" cards; editions with the same name now show their song count, year and whether they're explicit.
- **Two "New releases" on Home:** Prism's own section (from Release Radar) is now called **New from your artists**, so it isn't confused with YouTube's shelf.
- **Sing's engine (ONNX Runtime) has its own usage reporting, now switched off outright.** It could never start on Android (it has no way to send), so nothing was ever sent, but Prism doesn't leave that to chance.
- **"Pick artists you like" (first start):** the Done button sat under the tab bar, which now stays hidden until setup is finished; the genre chips all showed the same hip-hop artists (YouTube's genres share one page address, and Prism told them apart by that alone), and genres whose pages list only albums and playlists (Rock, Pop) came up empty. Each genre now shows its own artists, with their photos (some big names, like Drake, showed blank).
- **Bluetooth earbuds (AirPods especially) could make the music skip or judder.** Earbuds register and drop several audio devices as they work (their microphone, call channels, ear detection, the case), and each one made Prism re-point its audio output, even when nothing had changed; on Bluetooth each re-route is a short gap. Prism now only lets go of an output you picked, and only for a real new output. Headphone correction waits for the devices to settle before switching. Sing's processing no longer runs on every sample of music when Sing is off, and the song scans behind Normalize, Crossfade and DJ mode wait while the playing song is still loading.
- **DJ mode took minutes to get a set in order**, reading every song whole, one at a time. It now hears three stretches of each song, three songs at once (the key matched the whole song's for 26 of 28 test songs, and the tempo for 27). On mobile data it used to say "in key order" while it couldn't read a single key; it now says what it's doing ("reading keys… 4 of 24", "reading keys… 0 of 1, 41 wait for Wi-Fi" with a **Use mobile data** button, "10 of 24 songs in key order").
- **Daily Mixes could skip a number** ("Daily Mix 1", "Daily Mix 3") when a group of artists didn't have enough songs for a mix.

## 1.6.9.2

### Fixed
- **Discord sign-in stayed on Discord's page after signing in.** Prism now picks up the session from Discord's own sign-in answer (password, two-factor or QR code) and its requests, checks on every page rather than only once the address changes, and finds Discord's session wherever its current code keeps it.

## 1.6.9.1

### Fixed
- **Discord sign-in showed a blank page** when Discord's page couldn't load (no connection, or a network that blocks Discord, as many work and school ones do). It now says it couldn't reach Discord, with Try again.
- **Discord sign-in** looks like the phone's browser to Discord (Android's in-app browser marked itself), and reads the sign-in a second way if the first doesn't find it.

## 1.6.9

### New
- **More than one account**: Settings → "Switch account" lists every YouTube channel you can use, including several channels (brand accounts) under one email, and "Add a Google account" signs another one in beside it. Switching brings that channel's library, likes and recommendations; Replay and this phone's history stay.
- **Discord** (Settings → Discord): "Listening to Prism" on your Discord profile, with the song, artist and how far in, cleared when you pause. Off until you sign in to Discord on its own page; the sign-in stays on this phone and never goes in a backup. Discord has no official way for Android apps to do this, so it works the way other Android music players do, which Discord's terms don't allow for third-party apps: there's a small risk to your Discord account.
- **Cupertino icons** (Settings → Appearance → Icons → Pack): the most-seen icons redrawn in the style of Apple's apps (filled tabs, a chevron for Back, two-triangle skips, circled menus and downloads).
- **Remove from library** in an album's long-press menu.

### Fixed
- **Lyrics stuck on the last line** from the start of a song ("4 Steps Back, 2 Steps Forward" by Motion Sick): one source sent plain lyrics marked as synced, every line at 0:00. Those now count as plain, so a source with real timings wins.
- **Quick picks popping in and out**, and Home showing the same songs twice: Quick picks and Speed dial no longer reload each time they scroll off screen, a refresh without Quick picks keeps the last ones, and a section already shown isn't repeated.
- **Slow starts and buffering since 1.6.8**: saving playlists, albums and covers for offline, and retrying failed downloads, now wait while music is loading, and do less at a time.
- **A crash** when Prism started in the background with failed downloads waiting (Android 12 and newer).
- **Albums that wouldn't leave the library** when YouTube opened them as another edition (a remaster or deluxe).

## 1.6.8

### New
- **Speed dial** on Home (Customize Home → Add a section), as on YouTube Music's app: your go-to songs, albums, playlists and artists as big tiles, nine to a page. YouTube's own Speed dial (or Listen again) leads, filled out with what you play most. It goes in at the top (#53).
- **Quick picks start a radio**: tapping a pick plays it and then songs like it, as on YouTube Music. Customize Home → Options turns it back to playing Quick picks in order (#38).
- **Search your own music**: Search has YouTube Music and **On this phone**, which finds your music files and downloads by title, artist or album. Offline, Search starts there (#46).
- **Offline library**: your playlists, albums and artists are kept as last synced, and playlists and albums are saved with their songs when opened (and on Wi-Fi after a sync), so they open with no connection. Offline, a playlist plays its downloaded songs and shows the rest greyed out (#55).
- **Covers offline**: downloaded songs' covers (a lossless file's own embedded cover first) and your playlists' and albums' are kept on the phone, for the player and its blurred background, the notification, widgets and lists. Local files whose album has no artwork in Android show the cover inside the file (#54, #55).
- **Data saver** (Settings → Playback & sound → Mobile data): on mobile data, pictures come at the size they're shown, artist photos and animated covers wait for Wi-Fi, songs stream at your mobile data quality (at most Normal) and music videos at 480p at most (#40).
- **Songs, not videos** (Settings → Playback, on by default): autoplay and radios play music videos as their songs and leave out videos with no song version (#39).
- **Stop when swiped away** (Settings → Playback): swiping Prism out of your recent apps stops the music (#45).
- **Choose your lyrics sources**: each source in Settings → Lyrics → Sources has a switch and a line on what it's good for; Prism only asks the ones switched on (#51).
- **Save lyrics into your music files** (Settings → Lyrics, or a song's ⋯ menu): synced lyrics go into your FLAC and MP3 files' tags as LRC, so other players show them too (#16).

  > **About #16:** Prism has always left your own music files alone, so this only happens when you ask for it: nothing is written until you tap it in Settings → Lyrics or a song's menu, and on Android 11 and newer Android asks you to allow the change first. Only the lyrics tag is written; the audio and every other tag stay as they were. Each file is copied first and checked before it goes back (if anything goes wrong, even Prism closing mid-way, the original is put back). Prism still never deletes or moves your files.
- **New widgets**: Cover (2×2), Disc (2×2, with a progress ring) and Strip (4×1) (#34).
- **Local files sorting**: a sort sheet with the direction in plain words and more orders (date added, year, length, file size), plus orders for Artists and Albums (#35).
- **Every artist of a song**: a song's menu has "Go to …" for each of its artists, and tapping the artist on the player asks which one (#42).

### Fixed
- **Downloads stuck at a percentage** (#55): failed downloads now try again by themselves at launch and when you're back online, with fresh links; one that stalls restarts from where it got to; and Downloads says when they're waiting for Wi-Fi or failed, with "Use mobile data" and "Try again". Offline, songs that aren't fully on the phone are passed over instead of cutting off mid-song.
- **Music videos with the Song / Video switch off** (#48): a music video picked from a list plays as its song, and nothing switches to video.
- **Headphone volume lag** (#52): the player's volume slider only sends real changes, so Bluetooth headphones no longer fall seconds behind it.
- **Grid** for Quick picks and other song sections shows covers in two rows; it looked the same as List (#44).
- **Artist pages** (#47): the photo's colors carry on down the page and settle into its own tint instead of fading to black (album and playlist pages too). The artist's name moves with the photo as you scroll (#37).
- **The player's title** is a size larger than the artist under it; they were both 20 sp (#41).

## 1.6.7

### New
- **Your own fonts** (Settings → Appearance → Text): pick a .ttf or .otf file from your phone (Inter, SF Pro…) for the whole app, and choose a separate **lyrics font**: the app's, any built-in font, or a file of its own. Variable fonts get all their weights (#12).
- **Icon style** (Settings → Appearance → Icons): Thin, Regular or Bold icons, rounded or square. Applies after a quick restart, from the button that appears (#19).
- **App icons** (Settings → Appearance → App icon): Classic, Spectrum, Solid, Light, Wave and Midnight (#20).
- **Local files, reworked** (#29): Play, Shuffle and **Recently played** at the top, the song and album count under the title, songs sorted by title by default (the order you pick is kept), and new sections: **Favourites** (liked songs from your phone), **Recently added**, **Added albums** and **Downloads**. Tap **Edit** at the end of the bar to choose and order the sections.
- **Hide the Podcasts button** on Home (Settings → Home screen → Podcasts button); Podcasts then opens from Library (#28).

### Fixed
- **Lossless FLAC at double speed with harsh noise** on Samsung phones (#25): Samsung's FLAC decoder mishandled 16-bit (CD-quality) files in lossless mode. They're now decoded at 16-bit, which loses nothing; 24-bit files stay hi-res.
- **Searches showing up in your YouTube search history** (#27): Prism's searches, every keystroke and the lookups it makes for songs, were saved to your YouTube account. Searches no longer go out with your account.
- **Podcasts "Requested entity was not found"** (#18): in countries where YouTube Music has no Podcasts page, Prism shows your followed shows and saved episodes, and points to Search, instead of an error.
- **Phones in other languages** (Korean, Spanish and more): YouTube answers in your phone's language, and Prism misread its words for "Song", "Single", "Album", years ("2026년") and listener counts, which mixed up artists, album types and search ranking. They're understood in 20 languages now. The date on Home follows your language's order. (Prism's own screens are still in English.)

## 1.6.6

### New
- **Hold previous / next to seek** (Settings → Player → Hold to seek, on by default): holding jumps back or forward, again and again while you hold. Pick 1–20 seconds per step, or turn it off. A tap still changes songs (#21).
- **Local files as a tab** (Settings → Layout & navigation → Tabs): put Local files in the bottom bar, and open Prism on it if you like (#17).
- **Song / Video switch** (Settings → Player → On the player): turn it off to keep the player audio only (#22).
- **"Hey Google, play … on Prism"**: Google Assistant and Gemini can now pick Prism as the app to play from. "Play music on Prism" carries on with your last queue (#23).

### Fixed
- **Right-to-left lyrics on the player:** the arrow after an Arabic line now sits on its left, at the end of the line, and points left (#14).
- **Podcasts** showed "Requested entity was not found" for some signed-in accounts. Prism now loads the page as a guest when YouTube refuses it for the account (#18).
- **Closing the player:** swiping it down and pressing Back now slide it away the same way (#26).

## 1.6.5

### New
- **Immersive mode** (the player's ⋯ menu, or Settings → Player): the cover fills the whole screen, its animated version first when it has one, and the lyrics play over its lower part a line at a time, like a lyric video. Everything else hides; tap the screen for a settings button at the bottom (back to the normal player, lyrics timing and source, the song's options), or **hold it** until its ring fills to go straight back to the normal player. Swipe left or right for the next or previous song, and drag down to close the player. The cover can fill the screen, be **zoomed out** (the default) or show **whole**, over a blurred copy of itself (the settings button, or Settings → Player). The lyrics can sit **high**, in the **middle** or **low** (the default) on the screen, or run **over the whole screen**: every line, the one being sung large and bright, the list gliding along by itself.
- **Crossfade** (Settings → Playback & sound, formerly Mix) now works like Spotify's: the ending song fades out while the next one fades in, both at their own speed. **Automatic length** (on by default) works out each crossfade from how the songs end and begin: over a song's own fade-out, over the next song's quiet intro after an abrupt ending, and only briefly between two hard edges. Or set 1–12 seconds yourself. Matching tempo and beats is now an option (DJ-style), off unless you turn it on. Songs already measured are measured once more for their fade-outs and intros.
- **Local files** has its own page (Library → Playlists → Local files), with **Songs**, **Artists** and **Albums**. Artists open to their songs album by album, and albums play in track order.
- **Shuffle and repeat on the player** (Settings → Player → Shuffle and repeat buttons): either side of play, always. Repeat goes all, one, off.
- **Load ahead** (Settings → Playback & sound): besides the next song's first seconds (as before), the next song or the next three can be loaded whole while one plays, so they play without buffering even if the signal drops. On Wi-Fi, or on mobile data too if you allow it (for the next song's start as well).
- **Swipe a song** in an album or playlist (Liked songs, Downloads and Local files too): right to **play it next**, left to **add it to the end of the queue**.
- **Android Auto lyrics: Keep the song title** (Settings → Lyrics → Android Auto, with the Karaoke or Line by line style): the lyrics show only on the cover, so the car's small media card reads as usual and its full player shows the lyric card big.
- **Settings tidied:** the Player page has separate Artwork, Immersive mode and Animated covers groups, and "On the player" lists things top to bottom as they sit on the player ("Hide volume bar" is now "Volume bar"). Lyrics sources and how they're picked share one group. The same bar is called the progress bar everywhere.

### Fixed
- **The player sits higher again,** as before 1.6.4: the cover and the play, back and forward buttons had moved down. The lyrics, Song/Video and queue buttons along the bottom stay where they are, and the title still sits close to the progress bar.
- **Quick picks** showed your recent plays again: YouTube's "Listen again" / "Speed dial" shelf was taken for them. When YouTube has no picks, songs like your latest play stand in, never the songs you just played.
- **Animated covers:** after saving one song's animated cover, the album's other songs showed their still cover. Looking up covers for saved albums and downloads no longer holds up the cover of the song that's playing.
- **Songs start faster with Crossfade or Normalize volume on.** Measuring a song (which can mean fetching and decoding all of it) ran just as a song started, slowing its loading. Now the next three songs in the queue are measured (and their loudness looked up) while the current one plays, once it's under way, at low priority.
- **Normalize volume no longer jumps a few seconds into a song.** A song's volume is set as it starts; a loudness figure that only arrives later is kept for next time instead of changing the volume mid-song. Queued songs have theirs ready before they play.
- **Animated covers keep playing** while the ⋯ menu or another sheet is open over the player, instead of switching to the still cover.
- **Shuffle:** Previous went to a song that hadn't played yet. It now goes back to the song played last, including after songs are added to the queue or a song is picked from it, and Play next plays next while shuffled too.

## 1.6.4

### New
- **Cover size** (Settings → Player → Artwork): Standard, **Large** (narrow side margins, like YouTube Music) or **Full width** (the square cover runs edge to edge). With Large or Full width, a hidden volume bar or output device gives its room to the cover (#10).
- **Stream quality** (Settings → Player, where "Stats for nerds" was) shows or hides the codec and bitrate (or "Downloaded") under the progress bar. The stats line over the cover is gone; tap the label, or use the ⋯ menu, for the full stats (#8).
- **Animated covers are saved with downloads**, so downloaded songs keep their moving cover offline. "Save with downloads on mobile data" decides whether that waits for Wi-Fi. An album's songs share one saved copy when they use the album's cover.
- **Quick picks snap** a page of rows at a time when swiped, like YouTube Music (#11).

### Fixed
- **Arabic and other right-to-left lyrics** sit on the right and read right to left, and word-by-word lyrics light up from the right (#7). The line on the player is right-aligned too.
- **Time-synced lyrics from YouTube Music** (LyricFind) are used when a song has them, so songs that only had plain lyrics there now scroll in time, Arabic songs among them (#6).
- **With "Current lyric" off,** the player no longer keeps a blank line for it, and the title sits just above the controls on tall screens (#9).
- **Quick picks** showed your recent plays instead of YouTube's picks when YouTube was in another language (#11).
- **Android Auto's queue** (and the watch's and notification's) follows the shuffled order you hear, and tapping a song in it plays that song.

## 1.6.3

### New
- **Your music files.** Library → **On this phone** lists the music on your phone (MP3, M4A, FLAC, OGG, WAV…) with its own tags and album art, sorted by date added, title, artist or album, with Play and Shuffle. Songs play from the file, with normalization, EQ, Mix, lyrics and Replay. Prism asks once for access and only reads the files.
- **Smart Downloads is one storage space.** It now covers both the songs you stream (the old song cache) and the songs it downloads ahead, under one size (your old two sizes added together). When it's full, the songs you're least likely to play again make room, and liked songs are kept longest. **Smart Downloads on mobile data** lets it download without Wi-Fi, and **Clear Smart Downloads** empties it (songs you downloaded yourself stay).
- **Prism's own icons** everywhere: minimal, rounded line icons in place of Google's.
- **Search:** better top results. Abbreviations work ("adtr" finds A Day to Remember, also "rhcp", "mcr"), YouTube's own top pick is used unless it's a cover or remix you didn't ask for, a song wins over its music video, and suggestions start with your artists and recent searches.
- **Replay** lists your top 100 songs.
- Settings → About → tapping the version opens Prism's GitHub page.

### Fixed
- **Normalize volume** really evens songs out now: each song's loudness is measured on your phone (LUFS, as Spotify and YouTube use) and brought to the same level. YouTube's own figure, used before, hardly differed between loud and quiet songs. The next song's level is ready before it starts. Stats for nerds shows the measured loudness.
- **Mix** blended into silence, or not at all, on songs whose music ends before the file does (a fade-out or a silent tail): blends are now timed from where the music ends, the next song comes straight in if the ending one goes quiet, silence at the start of the next song is skipped, and a slow-loading ending gets more time. "Measure songs on mobile data" (Settings → Playback & sound → Sound) covers Mix and normalization; songs already on your phone are measured without data.
- **Scrubbing** after changing songs could jump too far (it used the first song's length).
- **The player's ⋯ menu** stuck or stuttered when pulled down on songs with animated covers.
- **"Alright" (Kendrick Lamar)** and other long explicit songs froze the player for a moment as they opened, while starred-out words were filled in; songs with long music videos also opened sluggishly.
- **Music videos with a long intro** (like "Alright") have their lyrics lined up again.
- **After a podcast,** music plays as the song again, not its video.
- **Live streams** load again when signed in.
- **Home's frosted background** follows the song that's playing (#3).
- **Search:** the search box has its background back, and a top result's play button turns into pause (#5).
- **Replay:** genre names are readable with a white accent in light mode.

## 1.6.2

Everything since 1.6.0 (1.6.1 was tagged but never released).

### New
- **Podcasts.** The round podcasts button at the top of Home, beside Customize, opens them.
  - **Customize button at the top** (Settings → Home screen) can be turned off; Podcasts then takes its place.
  - **Continue listening:** episodes you've started pick up where you left off.
  - **Your shows** and **Episodes for later** from your YouTube Music account, when signed in.
  - **Popular shows and episodes**, by category.
  - **Show pages:** newest episodes first, more as you go, save to library and share.
  - **Search:** new Podcasts and Episodes filters.
  - **Speed:** 0.75× to 2× from the player's ⋯ menu, for episodes only.
  - Episodes don't look up lyrics, don't blend with Mix, aren't followed by music radio and don't count in Replay.
- **Home screen widgets**, in four looks: Prism (frosted cover), Spotify style, Apple Music style and YouTube Music style. Each shows the cover, title, progress, previous/play/next and like; tapping the cover opens the player.
- **Content country** (Settings → Home screen → Region). Where YouTube Music isn't offered, pick a country where it is to get Home, charts and search back. Songs a label blocks in your country still won't play.
- **Mix on mobile data.** "Measure tempo on mobile data" lets beat matching work away from Wi-Fi (about 1–4 MB per song).

### Fixed
- **Pixel Watch:** previous and next sit beside play again, instead of like and shuffle.
- **Lyric timing:**
  - lyric files that shift their own timing (an `[offset]` line) were read without the shift, so every line was early or late;
  - some sources used an extended or radio edit's lyrics, which ran early or late; versions more than 8 seconds off are now turned down, and lyrics saved from those sources are checked again once;
  - while paused, lyrics no longer creep ahead and jump back.
- **Karaoke lyrics first.** Lyrics that light up word by word are now called **Karaoke** (Lyrics source shows "Karaoke", "Time-synced lines" or "Plain text"). They win over time-synced lines even when a source earlier in your order only has lines, and songs whose time-synced lyrics were saved before look for karaoke again.
- **Uncensored lyrics:** words starred out whole (`****`, as Apple Music's lyrics do) are filled in now, as in Childish Gambino's "IV. Sweatpants". Songs with a guest in the title find their full words again, and YouTube Music's or LRCLIB's lyrics fill in when Genius doesn't have the song.
- **Menus:** swiping a menu down (like the player's ⋯) could stutter, or pop back up after being flung away.
- **Player:** an interrupted swipe down could leave the player stuck part of the way down.

## 1.6.0

### New
- **Mix.** Songs blend into each other instead of stopping and starting (Settings → Playback & sound → Mix, off by default).
  - **Blend length:** 2 to 12 seconds.
  - **Match tempo and beats:** Prism measures each song's tempo on the phone, on Wi-Fi. The next song comes in on the beat and plays at the outgoing song's tempo while they overlap (pitch kept), then eases back to its own.
  - **No blend for:** an album's own tracks, live streams, music videos and Listen Together. These still play as before.
- **Translated lyrics.** A translation can show under each lyric line when a song is in another language.
  - **Where:** Settings → Lyrics, or the player's ⋯ menu.
  - **Language:** your phone's language by default, or one of 20 others.
  - **Privacy:** this is a machine translation, and the lyrics' text is sent to Google Translate.
- **"Next in queue".** Songs you add with Play next or Add to queue get their own heading in the queue. Add to queue now puts songs before autoplay's suggestions instead of after them.

### Faster
- **Songs start sooner.** Prism gets the likeliest next song ready ahead of time and, on Wi-Fi, saves its first few seconds. This covers:
  - the top search result;
  - the first song of an open album or playlist;
  - the next song in the queue, including after the queue changes or shuffle is turned on;
  - the song in the mini player when the app opens, and the one after it.
- **Results:** a song that was got ready starts in about half a second instead of over two seconds.

### Fixed
- **Back gesture animation:** the phone's back button or gesture now fades between pages like the back arrow does, instead of shrinking the page like a card.
- **Plain error messages:**
  - "You're offline", instead of technical text;
  - searching with no connection says so, instead of "Search failed";
  - a clear message when YouTube is limiting playback on the network.
- **Refused requests after signing in:** YouTube refused some requests ("Request contains an invalid argument") for some accounts and regions, such as brand accounts and es-419. Prism now retries them in a plainer form.
- **Blocked networks:** when YouTube blocks anonymous playback on a network, signed-in playback goes straight to the account's own clients for a while, instead of failing on each song first.
- **Bitrate:** the player shows each song's real average bitrate. It used to show YouTube's nominal figure (Opus always said 160 kbps).
- **Album tracks:** signed out, some albums' tracks opened as music videos. They now play as songs.
- **Switching between song and video** no longer counts as a new play in history and Replay.
- **Lyrics and queue scrolling:** the player controls no longer flicker in and out while you scroll lyrics or the queue (seen on 120 Hz phones).
- **Home:** a stale "Quick picks" row no longer flashes up while Home loads.
- **"See all" pages:** mixes and playlists now show as a two-column grid of covers, not one sideways row.
- **Fonts:** Google Sans and Rounded are greyed out, with a note, on phones that don't have Google Sans Flex. Picking them there used to do nothing.
- **Counts:** they read "1 song" and "1 artist", not "1 songs".
