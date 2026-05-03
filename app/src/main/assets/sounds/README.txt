Drop looped audio files here before building. Recommended format: OGG Vorbis,
seamless loop, 44.1 kHz.

Built-in voices that the app expects (filename -> tile):
  rain.ogg       -> Rain
  ocean.ogg      -> Ocean waves
  wind.ogg       -> Wind outside
  fan.ogg        -> Fan
  fireplace.ogg  -> Fireplace
  storm.ogg      -> Thunderstorm
  forest.ogg     -> Forest birds
  stream.ogg     -> Babbling stream
  cafe.ogg       -> Coffee shop
  ambient.ogg    -> Ambient pad

Files that aren't present at build time will be shown in the web UI as
"missing audio file" tiles - the app will not crash. You can also add your
own loops at runtime through the Library tab in the web UI; those go to
the app's private files dir.

Good free sources for loopable ambient audio: freesound.org, mynoise.net,
zapsplat.com, BBC sound effects library.
