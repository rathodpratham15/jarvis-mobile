# The guide character: the author's design, animated with Muse Image

The guide that pops up in live scan is a 3D-style character from the author's own character sheet (`Char-design.png`:
hero pose, expressions, turnaround). Its face is animated by swapping **Meta Muse Image edits** of the same picture, and
**SAM 3.1** locates the mouth and eyes so the patches line up.

> Origin of the character sheet: TODO (author to state which tool made it, for the submission).

## How it was made

1. **Source.** The hero pose was cropped from the character sheet and padded to a 2:3 canvas (the sprite's base image).
2. **Expressions (Muse Image, `/v1/images/edits`).** The picture was edited five times with the instruction
   *"Keep EXACTLY the same character (face, dark wavy hair, black hoodie with the glowing blue orb, cargo trousers, sneakers,
   smartwatch), the same pose with hands in pockets, framing, proportions, lighting and 3D animated-movie style. Replace the
   background with a perfectly flat solid pure green (#00FF00)... Change ONLY the following detail and nothing else: ..."*
   followed by one of: relaxed closed smile with eyes open (the base), mouth open wide ("ahh"), small round "ooh",
   wide smile showing teeth ("eee"), eyes closed in a blink.
3. **Placing the face (SAM 3.1).** SAM located the "mouth" and "both eyes" on the head; those boxes (plus padding) bound the patches.
4. **Assembly (`scripts/build_guide_sprites.py`).** Green screen removal with edge despill, alignment of each edit to the base,
   and feathered face patches. Only the patches change while the guide talks or blinks, so hair and clothes never flicker.
5. **Animation (`GuideView.java`).** The mouth picture follows the letters being spoken over the length of the audio, and the
   eyes blink every few seconds. The body stays still (the character's hands are in its pockets). It is a cartoon, not a
   likeness of a real person.

Total cost of the art: about ten generations at $0.01 each.

## Rebuild

```bash
python scripts/build_guide_sprites.py <folder with base.png closed.png ah.png oh.png ee.png blink.png>
```
Outputs `guide_*.png` into `app/android/app/src/main/res/drawable-nodpi` and `res/raw/guide_sprite.json`.
