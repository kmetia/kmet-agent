# Tcl/Tk backend — what is left

Working notes on the gaps of the Tk backend (`kmet.tk.*`), ordered by what
would matter first to an app UI built on it. The app port's own work, which
waits for these, is listed at the end. `https://github.com/kmetia/tk/blob/master/src/kmet/tk/README.md` is the
package's design document; this file is the open-work list beside it.

## What is complete

Every widget class Tk 9.1 has is a tag — classic `:tk/…` (window, frames,
text, button, label, labelframe, message, checkbutton,
radiobutton, entry, spinbox, listbox, menu, menubutton, panedwindow, scale,
scrollbar, canvas) and themed `:ttk/…` (frame, labelframe, label, button,
checkbutton, radiobutton, entry, combobox, spinbox, scale, scrollbar,
sizegrip, toggleswitch, menubutton, notebook, panedwindow, progressbar,
separator, treeview) — and each tag reaches its widget's whole option set
minus three kinds of option (variable-, script- and cross-widget), listed
below.

Images are supported on the widget surfaces the tags script: the image
props (`:image`, `:bitmap`, `:selectimage`, `:tristateimage`,
`:backgroundimage`, `:tile`) and the canvas items'
`:image`/`:bitmap`/`:disabledimage` take a Tk image name (a built-in bitmap
such as `:questhead`, or a name the app created) or a description map the
widget owns — a file or base64 data with `:format`, and the photo-only
`:zoom`/`:subsample`/`:fit` sizing — and a `:text` widget's `:content` can
embed one. The image is created (`image create photo`/`bitmap`), cached per
widget and description, and deleted with the widget; a name the app owns is
never touched. Item vocabularies outside the tag specs — a menu item's
`-image` — are not props.

The data widgets follow Tk's own vocabulary: a treeview hierarchy
(`:children`, the `#0` column, row tags and images), canvas items with
identity (an `:id` written as the item's first Tk tag, reconciled in place,
with Tk's item events and its stacking order), `:context-menu` posting
through Tk's own `tk_popup`, check and radio menu items with the private
variables Tk needs, and `grid`/`place` as a child's own geometry maps. What
each contract is, and what it refuses, is in `https://github.com/kmetia/tk/blob/master/src/kmet/tk/README.md` (Data
widgets, Props and bindings, Geometry).

The variable-backed options are props: `:variable`, `:textvariable` and
`:listvariable` take a kmet variable (`kmet.tk.core/variable`) — whose
Tcl name lives in a kmet namespace of its own, is materialized lazily,
and is watched through Tk's `trace add variable` (`variable-watch!`, which
is what carries a Tcl-side change back to a callback) — or a name the
app's own Tcl owns, which kmet scripts as it is and never unsets.
`:onvalue`/`:offvalue`/
`:tristatevalue` are plain props beside it, a checkable widget's variable
is shared with the app and with its radio group, and a second source of
truth (`:text` beside `:textvariable`, `:items` beside `:listvariable`) is
refused. `https://github.com/kmetia/tk/blob/master/src/kmet/tk/README.md` (Shared variables) is the contract.

The script-typed options are props too — binding slots of the tags whose
widgets have them. Tk asks `:validatecommand` whether an edit is allowed,
handing the handler `%P` (the value the widget would have), which answers
a boolean (false or nil refuses); `:invalidcommand` reports the refusal;
`:xscrollcommand`/`:yscrollcommand` (outside the `:scrollbar` sugar, with
which they conflict) hand the visible fractions to the handler as
numbers; `:postcommand` builds a `:tk/menu` or `:ttk/combobox` when it is
posted. Tk's `-browsecmd`/`-invokecommand` belong to BWidget/tklib
widgets, not core Tk, so no tag declares them.

The cross-widget options are reached where they mean something: `-menu`
through a menubutton's `:items` and a menu's `:menubar true`, and
`-labelwidget` through a labelframe child's `:labelwidget true` — a
placement prop like `:tab`, since a hiccup child cannot name a sibling.

Tk's native dialogs are `kmet.tk.core` functions: `message-box!`,
`open-file!`, `save-file!`, `choose-directory!` and `choose-color!` script
`tk_messageBox`/`tk_getOpenFile`/… with the props as that command's own
options and answer with Clojure data — a button keyword, a path, a colour
— or nil when the user cancelled (`:multiple` answers a vector of paths,
which only Tcl can split out of the list Tk returns). They block in Tcl's
nested event loop, so they must run on the Tk thread: call one from a
callback, not from a component body, which renders inside the pump.

ttk styles and themes are wired: `kmet.tk.ttk.style` is the style database
(`use-theme!`, `define-style!`, `map-style!`, `apply!`), and an app compiles
its own set from it (the sample that did reused the TUI theme and was dropped
together with that dependency). Re-applying the set when the *user* changes
the theme is app work: it is the last item of the app port at the end of this
file.

`:tk/text` is both the display surface and the backend's multi-line input.
`:editable true` keeps `-state normal`, turns Tk's undo on, takes `:text`
as the widget's value (read back with `get 1.0 end-1c` and written only
when it differs, so mirroring `:on-change` into `:text` never moves the
caret; a write resets the undo history, so undo covers only the user's own
edits) and binds `:on-change` (`<KeyRelease>`: typing, a paste and an undo
all end in one) and `:on-submit` (`<Return>`, ending in `break` so Tk's
class binding does not also insert a newline). `:content` is refused on an
editable text. The composer built on it (`:ui/composer`) is app work too —
it waits for the app port, listed at the end of this file.

The sample app's widgets are not part of those sets: they are `:ui/…` tags in
`kmet.gui-sample.ui` (today `:ui/transcript`, the chat scroll container), added to
the backend through `kmet.tk.core/mount-backend!`/`run-backend!`.

Two guards keep that honest, and are the place to start from when adding a
widget (the `:ui/…` widgets have their own guard, `bb test
kmet.gui-sample.test-ui`):

```sh
bb scripts/bench/tk_option_audit.clj          # declared vs. the live widget, per tag
bb test kmet.tk.test-tcl               # the same claim in test form: every
                                        # tag's options and callback options
                                        # checked, every tag mounted with all
                                        # its props set
```

The audit's per-widget dump (`--widgets`) prints each class's option list, so
a new tag starts from an audit line rather than from guessing.

A `:bind` value may be a map with a `:value` kind instead of the handler
alone, so the bound script hands the handler a live value the way a slot
does — a `:pointer` drag on a canvas keeps its `[x y]` after the pointer
leaves the item, where Tk's item bind stops firing. The kinds are the
slots' own (see kmet.tk.dsl/value-kinds), except the ones an option
itself supplies (`:validate`, `:scroll`), which belong to that option.

## Tk 8.6 vs 9.1 — what the running version changes

The tag table is kmet's, not Tk's: the tag names are the same on every
supported Tk (8.6, 9.0, 9.1). What a version changes is which
*options* exist and whether a widget *class* is there at all. Measured here
against both interpreters on one machine (Tk 9.1.0 from the kmet bundle, Tk
8.6.18 from the Git for Windows bundle):

```sh
KMET_TCL_LIB=…/tcl86.dll KMET_TK_LIB=…/tk86.dll bb scripts/bench/tk_option_audit.clj
```

### Options Tk 9 added (they fail on 8.6)

| tag | 8.6 lacks |
|---|---|
| `:tk/entry` `:tk/spinbox` `:ttk/entry` `:ttk/combobox` `:ttk/spinbox` | `-locale`, `-placeholder`, `-placeholderforeground` |
| `:tk/frame` `:tk/toplevel` | `-backgroundimage`, `-tile` |
| `:tk/label` `:ttk/label` | `-textangle` |
| `:tk/text` | `-locale` |
| `:tk/listbox` | `-inactiveselectbackground`, `-inactiveselectforeground` |
| `:tk/menu` | `-activerelief` |
| `:ttk/button` `:ttk/checkbutton` `:ttk/radiobutton` `:ttk/menubutton` | `-justify` |
| `:ttk/progressbar` | `-anchor`, `-font`, `-foreground`, `-justify`, `-text`, `-wraplength` |
| `:ttk/treeview` | `-headingheight`, `-rowheight`, `-striped` |

Nothing goes the other way: no option exists on 8.6 that 9.1 lacks, so there
is no dropped or renamed option to carry. Tk's `-bd`/`-bg`/`-fg` (and 9.1's
`-bgimg`) are *aliases* in both, and kmet scripts the canonical name — the
audit treats the alias entries as non-canonical so they never show up as
gaps.

### Widget classes

One class in the tag table does not exist before Tk 9.0: `:ttk/toggleswitch`
(`ttk::toggleswitch`). Tk 8.6 has 36 widget classes, 9.1 has 37; every other
class in the table is in both.

### What happens when the running Tk is older

- **An option the widget lacks** fails at the first widget of that tag, from
  `dsl/check-options!` (it asks the widget for its own option list once per
  tag): `kmet.tk.core: :ttk/entry has no -placeholder on this Tk (prop
  :placeholder)` — a kmet message naming tag, prop and option.
- **A widget class that does not exist** fails inside Tcl at creation,
  before any option can be checked; `dsl/make-widget` catches that error
  and rethrows it with the tag and the interpreter's patchlevel:
  `kmet.tk.core: :ttk/toggleswitch (ttk::toggleswitch) does not exist on
  this Tk (8.6.16)`.
- **The test suite is version-aware on purpose**: on 8.6.16, `bb test
  kmet.tk.test-tcl` reports 17 failures and 1 error — the failures are its
  own option-membership assertions (the table above, one per tag, its
  binding options included), the error is the missing `:ttk/toggleswitch`
  class. It asserts the *running* Tk rather than a version range, so a
  version's gap is discovered rather than assumed.

So a kmet tree that stays inside the 8.6 option set runs unchanged on
8.6/9.0/9.1; a Tk 9 prop fails loudly at the first widget of its tag on an
older host, and `ttk::toggleswitch` needs Tk 9.0+.

## Tk beyond its widgets

- **Window management, multi-window included.** `mount!`/`run!` now drive
  several top-levels from one Tk event loop: the first window owns `.`
  and later mounts get a fresh `.win<n>` path; each handle carries its
  own dirty flag and pump, and the pump iterates every mounted window.
  Window-management helpers script against `.` by default; secondary
  windows are manageable through `wm-command!` or direct `wm ... <path>`;
  see `kmet.tk.core`'s `mount!`/
  `mount-backend!`/`run!`/`run-backend!`.
- **Drag and drop.** Cross-app drag and drop is covered where TkDnD is
  installed (`dnd-available?`, `dnd-require!`, `dnd-version`,
  `dnd-drag-source-register!`/`dnd-drop-target-register!`, plus their
  unregister/alias forms), which now match TkDnD's command signatures; in-app
  drags remain possible through bindings and `event-generate!`. Window
  management (including multi-window), clipboard/selection, scaling, focus,
  busy, virtual events and named fonts
  are now covered — see `kmet.tk.core`: `iconify!`/`withdraw!`/`deiconify!`,
  `minsize!`/`maxsize!`/`resizable!`/`attributes!`/`iconphoto!`/`on-close!`,
  `clipboard-*`/`selection-*`, `scaling`/`set-scaling!`, `tk busy` helpers,
  `focus-*`, `event-*`, and the `font-*` wrappers.

## Tk backend tasks specifically for the GUI live shell

The seam and the widgets are complete, so the GUI does not need a new
backend abstraction. The remaining Tk-side work is integration and
threading, not new widget support:

- Verify a fresh `kmet.gui-sample.*` shell can mount a tree, run its
  pump, and shut down cleanly with `mount-backend!`/`run-backend!`, the
  same way the display-only transcript demo does.
- Document and exercise an explicit `after idle` entry point so `subscribe!`
  callbacks running on the loop thread can be marshaled back to the
  toolkit thread when they need more than `render!` marks (all other Tk
  work already happens there). `render!` itself is already safe from any
  thread.
- Confirm the second-window pump dirty path under a long-running stream —
  cards, prompts, and dialogs opening in new `toplevel`s while a chat
  stream is active — without starving the component passes.
- Confirm dialog results can be delivered to asynchronous `respond!`
  consumers the application port wires; the dialogs themselves return
  Clojure data and block only the caller.
- Keep the shipped backend tests and the `:ui/transcript` container as the
  reference point: no new public `kmet.tk.*` API is expected for the live
  GUI, only exercise of the current one.

Nothing here is a missing widget or option: the tag set and dialog surface
already cover them.

## The app port (after the backend list)

The section above is the remaining `kmet.tk.*` gap list. This one is the
port's own work — the app's input and rendering, plus the seam extensions
need — and it starts once those gaps are closed.
Nothing here is a backend gap.

- **`:ui/composer`.** The chat input on the editable `:tk/text`: a
  content-sized height (Tk's display-line count), focus on mount, and the
  TUI editor's history.
- **Incremental append.** The transcript re-renders its whole stack as a
  session grows; appending a card should add one widget (`:ui/transcript`
  already hosts each card as its own embedded window, so the missing piece
  is an append path in the component rather than a re-render).
- **Image blocks in a card.** The image layer can display one, but a card
  renderer still renders a `{:type :image}` content
  block as the literal `[image]`: a card body is one height-counted `:text`
  widget, so an image needs its own widget, a pixel fit width (a card is
  measured in character columns), and a fallback for a format the running
  Tk cannot read (its built-ins are PNG, GIF, PPM and XBM; SVG on 9.1 —
  not JPEG).
- **Virtualization.** Mount only the visible rows of a long list; the
  scrolling itself is Tk's.
- **The extension-UI bridge.** kmet's own UI is written against `kmet.tui`;
  the GUI backend has no extension-facing seam yet, so no shipped extension
  can render a GUI surface.
- **The runtime theme switch.** Re-applying `kmet.tk.ttk.style`'s set when
  the user changes the theme (the set itself is complete — see What is
  complete).

## How to keep this list honest

- `bb scripts/bench/tk_option_audit.clj` — declared vs. live options per tag;
  exits non-zero when a tag declares an option its widget lacks, and prints
  the widget classes without a tag (currently none).
- `bb test kmet.tk.test-tcl` — the same check in test form plus a mount of
  every tag with every declared prop, a checkbutton/scale driven through
  Tk, and the dialogs answered by stand-in procs (their emitted option
  words asserted, no user); it skips where there is no display.
- `bb test kmet.tk.test-tk` — the headless recording adapter: tag
  construction, patching, callbacks, the value slots and disposal.
- `bb test kmet.gui-sample.test-ui` — the same for the sample's widget set (`:ui/…`),
  mounted through `kmet.gui-sample.ui/backend`.
- `jolt test kmet.tk.test-ttk-style` under a display (on Termux, `xvfb-run
  -a`) — the ttk style database round-trips through a real Tk; the same
  tests record the scripts headlessly on babashka.
