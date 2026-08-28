# Original font parity fixtures

`generate_fixture.py` creates the colored bitmap, Unihex data, and font definitions used by the independent loaded-client font oracle.
Every shape is original test geometry and is dedicated to the public domain under CC0-1.0.
The tests reuse the original TrueType file from `runtime/minecraft-fonts-lwjgl/src/test/resources/fonts/strata-test.ttf` through Gradle resource wiring.
No Minecraft, system, or third-party font pixels are used as candidate inputs.

Regenerate the bitmap and Unihex assets by running `python integration/minecraft-font-parity/fixtures/generate_fixture.py`.
