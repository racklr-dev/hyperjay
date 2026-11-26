# hyperjay

A **jacquard loom** for [jolt-microvm](https://github.com/racklr-dev/jolt-microvm).
Punch a card, weave it into a warp of live microVMs, and work the **circuit box**
from a GTK4 bench — jolt from top to bottom: [glimmer-gtk](https://github.com/jolt-lang/glimmer-gtk)
draws, jolt-microvm runs, libkrun hosts. Native Clojure on Chez Scheme, no JVM.

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
| **breaker** | one running VM in the circuit box — reboot it, cut it, or re-weave it  |

## Requirements

- a jolt binary at 0.8.28 or newer
- libkrun + libkrunfw and a writable /dev/kvm (see the jolt-microvm README)
- GTK4 and GLib: `apt install libgtk-4-dev` (or your distro's equivalent)
- a VNC client (for the desktop cards) and a host X server (for the single-app card)

## Run

From the repo root:

```sh
jolt run src/jolt/hyperjay.clj   # the bench: pick a card, work the breakers
```

The three cards also run headlessly without the bench:

```sh
jolt run cards/desktop-x11.clj    # X11 desktop over VNC
jolt run cards/desktop-wayland.clj# Wayland desktop over VNC
jolt run cards/single-app.clj     # one xterm on the host display over ssh -X
```

Or via the tasks in `deps.edn`: `jolt gui`, `jolt x11`, `jolt wayland`,
`jolt single-app`.

KVM is a supplementary group on most hosts, so the examples expect to run
under `sudo -u $USER --preserve-env`. The first weave apk-installs the card's
packages into the cached alpine base; later weaves reuse it.

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
src/jolt/hyperjay.clj         -main: the GTK4 app loop
src/jolt/hyperjay/cards.clj   the card catalogue
src/jolt/hyperjay/vm.clj      the loom: warp atom + weave!/cut!/reboot!/…
src/jolt/hyperjay/ui.clj      the bench: card rack + circuit box hiccup
cards/                       headless shims, one per card
```

## License

MIT — see `LICENSE`.
