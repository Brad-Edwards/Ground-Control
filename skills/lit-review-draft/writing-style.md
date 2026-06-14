# Writing-style guide - phase-5 drafting

This guide is the voice contract for `lit-review-draft`. It exists to make the
draft read like the author wrote it, not like a language model produced it.

**Derived from:** `tir-adan/raw/aces-research-docs/aces/aces-paper.tex` - Edwards et al.,
*ACES: Toward a Common Architecture for Agentic Cyber Environments* (2026). Examples
below are quoted from that paper. To widen the profile, add more samples by the same
author and re-derive.

**Scope.** This guide governs *voice* - sentence rhythm, vocabulary, stance toward
uncertainty, paragraph shape. It does **not** govern paper *structure* (that is the
phase-4 outline) and is **not** a licence to alter findings. ACES is an architecture
paper; the review being drafted may be a different paper type - the voice transfers,
the section structure does not.

---

## Part A - Voice profile (write like this)

**A1. Short declarative sentences land the point.** After a long, clause-heavy
sentence, a short one delivers the conclusion. *"These are not projections. They are
operational realities..."* / *"The pattern works. What is missing is an open
implementation."* / *"The components exist in fragments. The integration does not."*
Use this - but only when there is a real point to land. A short sentence with nothing
behind it is just choppy.

**A2. The argument advances by contrast.** Set up a distinction, then name which side
is right. *"Where Garg's taxonomy follows the content lifecycle, ACES decomposes by
architectural responsibility."* / *"The challenge is integration and scale, not
invention."* The `not X, but Y` and `X. But Y.` patterns are the engine of the prose -
each paragraph should move by drawing a line, not by accumulating adjacent facts.

**A3. Concrete and specific.** Real numbers, names, versions, amounts: *"EUR 3.3M",
"version 0.0.2", "16 EU member states", "4,000 participants from 41 nations defending
8,000 virtualized systems", "145 organizations"*. When a specific count exists, use it.

**A4. Claims stated flat; uncertainty located, not hedged.** Assertions are made
directly: *"OCR SDL's semantic model is the strongest available foundation." "The
limitations are real."* When something is genuinely unknown, it is *named* as a
specific open question - *"an open design question", "an empirical question that
requires the architecture to exist before it can be studied"* - not smeared across the
sentence with "may", "could", "arguably". Locate the uncertainty; do not hedge it.

**A5. Active, agent-forward.** Sentences have actors. *"We introduce... We survey... We
characterize... We evaluate."* The paper does things; prefer the active construction
that says who does what.

**A6. The precise term, repeated - not elegant variation.** Use the exact domain term
consistently (*"instantiation", "backend-agnostic", "semantic model"*). Do not swap in
synonyms to avoid repeating a word; in technical prose the repetition is clarity, the
variation is noise.

**A7. Em-dashes carry content.** Dashes are for appositive elaboration and embedded
lists - *"two open-source foundations---the OCR SDL and OCSF---that provide..."* - not
for rhythmic decoration. Test: if a dash pair can be deleted with no loss of meaning,
delete it.

**A8. Paragraphs end on a point, not a summary.** The closing sentence sharpens, turns,
or draws the consequence. It does not restate the topic sentence. *"All four validate
the same pattern... The pattern works. What is missing is an open implementation."*

**A9. Weakness is addressed head-on.** Name the objection and answer it or concede it.
*"The limitations are real." "The honest position is that..."* A discussion that hides
its soft spots reads as evasive; one that names them reads as credible.

**A10. Openers orient briefly, then move.** A section or paragraph may open with one
short orienting sentence that states its job - *"A specification language, however
rich, is half the architecture."* - then it gets to work. No throat-clearing.

---

## Part B - Blocklist (never this)

Run this list literally against the draft in the phase-5 blocklist pass. Zero hits.

- **Connective filler as a reflexive opener.** "Moreover,", "Furthermore,",
  "Additionally,", "Notably,", "Importantly," beginning a sentence that carries no
  actual escalation or contrast. (A connective is fine when it marks a real turn; it
  is a tell when it is reflexive.)
- **Throat-clearing phrases.** "It is worth noting that", "It is important to note",
  "It should be noted that", "It is interesting that".
- **Throat-clearing openers.** "In recent years,", "In today's world,", "As technology
  advances,", "With the rapid development of".
- **Hedge stacks.** "may potentially", "could arguably", "it could be argued that",
  "to some extent", "in many ways", and filler "relatively" / "somewhat". (See A4 -
  locate uncertainty instead.)
- **Decorative adjectives.** "robust", "crucial", "vital", "pivotal", "seamless",
  "powerful", "cutting-edge", "state-of-the-art", "comprehensive", "significant" used
  as decoration rather than to carry a specific, defended meaning.
- **Inflated diction.** "delve", "leverage" as a verb-of-all-work (use "use"),
  "utilize" (use "use"), "tapestry", "realm", and "landscape" used decoratively
  ("ever-evolving landscape").
- **The reflexive tricolon.** Three-item lists "X, Y, and Z" used as decoration in
  sentence after sentence. Lists are fine when the three items are load-bearing; the
  tell is the rhythm applied regardless of content.
- **"Not only... but also"** as a reflexive construction.
- **Empty summary sentences.** "In conclusion, X remains a complex and multifaceted
  challenge." "Ultimately, the importance of X cannot be overstated."
- **Metadiscourse - narrating the paper's own speech acts.** "The review states",
  "this review finds", "the paper argues that", "carried as a declared limitation",
  "it should be emphasized that". Make the claim; do not narrate the paper making it.
  Ordinary framing - "We surveyed 52 sources", "This paper introduces" - is fine; the
  tell is *recurrent* narration of the review's own assertions standing in for the
  assertions.
- **Vague "this".** "This shows that..." with no referent noun - write "This
  separation shows...", name the thing.
- **Vague quantifiers where a count exists.** "various", "numerous", "a number of",
  "several" when the evidence base gives a specific number (see A3).
- **Symmetrical paragraph closers** that restate the topic sentence (see A8).

---

## How the phase-5 style pass uses this

After each section: read it once for **Part A conformance** (does it have the rhythm,
the contrast structure, the concreteness, the flat claims?) and once for **Part B
hits** (search the text for each blocklist item). Revise until Part A holds and Part B
is empty. A draft that is grounded and correct but fails this guide is not done.
