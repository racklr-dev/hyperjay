# hyperjay

A **jacquard loom** for [jolt-microvm](https://github.com/racklr-dev/jolt-microvm).
Punch a card, weave it into a warp of live microVMs, and drive them from a
UTM-like GTK4 bench — sidebar library, embedded display, inspector.
[glimmer-gtk](https://github.com/jolt-lang/glimmer-gtk) draws the UI, jolt-microvm
boots the VMs, and libkrun hosts them. Native Clojure on Chez Scheme, no JVM.



https://github.com/user-attachments/assets/8244db85-8892-4df0-92da-70b20468c3c6




## The metaphor

| word        | what it is here                                                        |
|-------------|------------------------------------------------------------------------|
| **card**    | a punched recipe — data that describes a microVM to weave              |
| **loom**    | the manager: cards in, running microVMs out                            |
| **warp**    | the set of running VMs — the long threads held under tension           |
| **weft**    | the shuttle of status and log events passing through the warp          |
| **weave**   | start a VM from a card (`weave!`)                                      |
| **cut**     | stop a VM and drop its thread (`cut!`)                                 |
| **shuttle** | the background thread that carries boot work so the bench stays free   |
| **breaker** | one running VM in the warp — select it, reboot it, cut it, or re-weave it  |

## Requirements

- a jolt binary at 0.8.28 or newer
- libkrun + libkrunfw and a writable /dev/kvm (see the jolt-microvm README)
- GTK4 and GLib: `apt install libgtk-4-dev` (or your distro's equivalent)
- a display for GTK4: X11 or Wayland, or `Xvfb` for headless smoke. The
  embedded GL canvas wants a GL context (hardware or llvmpipe). The single-app
  card forwards xterm to the host display over `ssh -X`.

## Run

From the repo root:

```sh
jolt run -m jolt.hyperjay        # the bench: sidebar, display, inspector
```

The three cards also run headlessly without the bench:

```sh
jolt run cards/desktop-x11.clj    # X11 desktop on the virtio-gpu scanout
jolt run cards/desktop-wayland.clj# Wayland desktop on the virtio-gpu scanout
jolt run cards/single-app.clj     # one xterm on the host display over ssh -X
```

Or via the tasks in `deps.edn`: `jolt gui`, `jolt x11`, `jolt wayland`,
`jolt single-app`.

KVM is a supplementary group on most hosts, so the examples expect to run
under `sudo -u $USER --preserve-env`. The first weave apk-installs the card's
packages into the cached alpine base; later weaves reuse it.

The bench draws the guest framebuffer natively — no VNC server, no external
viewer. The sidebar lists running warps (click to focus one), your saved warps,
and the card rack. The focused thread's desktop appears in the content pane, and
the inspector holds its actions: **Attach console**, **Reboot**, **Refresh
desktop**, **Cut** (plus **Re-weave**/**Discard** for a failed thread), and a
**Save to library** form. Keyboard input routes to the focused thread, and saved
warps persist across runs.

## The cards

Cards are data, in `src/jolt/hyperjay/cards.clj`. A card is a map:

```clojure
{:id :desktop-x11
 :name "X11 desktop"
 :warp "one-line description"
 :opts {:image "alpine:3.20" :packages [...] :memory-mib 1024 :cpus 2}
 :finish desktop/start-x11!}   ; (fn [vm] -> connection map), or nil
```

To add a card, punch a new entry in the catalogue and (optionally) a headless
shim under `cards/`. The loom merges `:project-dir` at weave time, so cards
stay pure data.

## Layout

```
src/jolt/hyperjay.clj          -main: the GTK4 app loop
src/jolt/hyperjay/cards.clj    the card catalogue
src/jolt/hyperjay/vm.clj       the loom: warp + focus atoms, weave!/cut!/reboot!/…
src/jolt/hyperjay/library.clj  the saved-warp library (persisted EDN)
src/jolt/hyperjay/ui.clj       the bench: sidebar + content + inspector hiccup
src/jolt/hyperjay/display.clj  the embedded GL canvas + keyboard forwarding
src/jolt/hyperjay/gtkx.clj     small GTK4 extras (selectable text + clipboard)
cards/                         headless shims, one per card
```

## License

EPL-2.0 — see `LICENSE`.
