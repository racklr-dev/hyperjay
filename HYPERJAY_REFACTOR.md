# hyperjay refactor — native display, UTM-like bench

Status: accepted 2026-11-26. Path B only (no in-app RFB client, no vncviewer).

## Goal

Cut the external `gvncviewer`/`vncviewer` hack and the in-guest `x11vnc`/`wayvnc`
servers, and present the VM framebuffer **natively inside the bench** through
libkrun 2.0's virtio-gpu display path. Then reshape the bench into a
UTM-like product: sidebar library, embedded display, inspector, guided
creation, snapshots.

## The decisive facts (all verified against upstream libkrun main)

- libkrun 2.0 ships a **native display + input path** that `jolt-libkrun` does
  not bind yet: `include/libkrun_display.h` and `include/libkrun_input.h`.
  It is the UTM-native route — no VNC server, no RFB decode, no gvncviewer.
- The display path is a host **vtable** libkrun drives: `configure_scanout`,
  `alloc_frame` (hands us a writable framebuffer pointer libkrun fills), and
  `present_frame` (with an optional damage rect). Pixel formats are virtio-gpu's
  (`B8G8R8A8`, `X8R8G8B8`, `A8R8G8B8`, `R8G8B8A8`, …).
- The input path is an **event-provider vtable**: `get_ready_efd` (an eventfd
  that becomes readable when input is queued) + `next_event` (pops a Linux
  input event `{type u16, code u16, value u32}`). Keyboard and/or touchscreen.
- Both devices attach to the **MMIO device manager** already used for
  fs/net/console (`devices.add(gpu)` / `devices.add(input)`), per libkrun's
  `examples/gui_vm/src/main.rs`.
- The guest kernel is libkrun's bundled **krunfw**, and its x86_64 + aarch64
  configs already have `CONFIG_DRM_VIRTIO_GPU=y`, `CONFIG_DRM_VIRTIO_GPU_KMS=y`,
  `CONFIG_VIRTIO_INPUT=y`. **No kernel rebuild.**
- The reference implementation to port from is
  `examples/krun_gtk_display/` + `examples/gui_vm/` in containers/libkrun.
- `jolt-libretro` already proves jolt fns work as C callbacks
  (`ffi/foreign-callable … :collect-safe`, released with `ffi/free-callable`);
  that is the pattern for the display/input vtables.
- Backend support is unchanged and correct: one backend (libkrun), two host
  hypervisors (Hypervisor.framework on macOS, KVM on Linux), already selected
  by libkrun and pre-flighted by `jolt-microvm/check!`.

## virgl flags (`krun_gpu_device_new` first arg)

virglrenderer flag bits (from libkrun `src/devices/src/virtio/gpu/virtio_gpu.rs`):
`USE_EGL=1<<0`, `THREAD_SYNC=1<<1`, `VENUS=1<<6`, `NO_VIRGL=1<<7`,
`ASYNC_FENCE_CB=1<<8`, `RENDER_SERVER=1<<9`, `DRM=1<<10`.

- Reference gui_vm uses `USE_EGL|VENUS|RENDER_SERVER|THREAD_SYNC|USE_ASYNC_FENCE_CB` = 835.
- For the 2D-only MVP (guest software-renders, we only need the scanout) pass
  `virgl_flags = 0` first; fall back to 835 if the scanout needs it.

## The frame seam (new library: jolt-framebuffer)

One canonical `frame` value, produced by any framebuffer source and consumed
by any sink:

```clojure
{:width 1280 :height 720 :format :x8r8g8b8 :stride 5120
 :bytes #object[byte-array]}   ; packed pixels, stride bytes per row
```

- `jolt.framebuffer/rgba` decodes a frame to a canonical flat RGBA byte-array
  (`4*w*h`), with a format table keyed by keyword. The decoders come from
  `jolt-libretro/src/libretro/run.clj` (0RGB1555, XRGB8888, RGB565) plus the
  virtio-gpu formats above. Alpha is treated as opaque: map the `*A*` formats
  onto their `*X*` equivalents (the reference does exactly this to avoid
  white-on-white blending).
- A sink is display-agnostic and just receives frames (PPM dump, glimmer-gl
  texture, later GIF). This is the shape `jolt-libretro` already models.

## Milestones

### M0 — extract `jolt-framebuffer` (new racklr-dev repo)

Spin the framebuffer decode + sink pattern out of `jolt-libretro/run.clj` into
a tiny purpose-built library: frame map, format table, `rgba` decode, sink
protocol. No FFI, no VM — pure byte work. Tests decode each format against
known pixel values.

### M1 — jolt-libkrun: bind the display path

Add to `jolt.libkrun`:

- `krun_display_backend_new` (vtable*, vtable_size, err*) → backend
- `krun_display_backend_add_display` / `_destroy`
- `krun_display_info_builder_new` (w, h) / `_destroy` (dpi/physical_size/
  refresh_rate/edid_name optional)
- `krun_gpu_device_new` (virgl_flags u32, backend) / `_destroy`
- build the `krun_display_backend` struct in jolt (96 bytes):

```
u64 features @0        ; BASIC_FRAMEBUFFER=1
ptr create_userdata @8
ptr create          @16 ; int32 (void**, const void*, const void*)
ptr vtable[9]       @24 ; destroy, disable_scanout, configure_scanout,
                        ; alloc_frame, present_frame, import_dmabuf,
                        ; unref_dmabuf, configure_scanout_dmabuf, present_dmabuf
```

- jolt-side `create` callback: `ffi/alloc` a token, register it in a jolt atom
  `token → {:scanouts {}}`, write the token into `*instance`, return 0. Every
  later callback looks its state up by the token.
- `alloc_frame`: allocate `w*h*bpp` (or reuse a pool), write pointer + size out,
  return frame_id (1). `present_frame`: copy the buffer into a `jolt.framebuffer`
  frame and hand it to the sink; return 0. Both must not block long (copy is
  ~4MB memcpy at 1280x720x4 — fine; pool later).
- Wire `gpu-dev` into the device manager beside fs/net/console in `boot!`,
  behind an `:display` opt so non-GUI boots are unchanged.
- **Headless smoke test**: boot alpine with `:display {:width 1280 :height 720}`,
  a display backend that writes frames to PPM; assert pixels arrive. This proves
  the whole native path before any UI work.

### M2 — hyperjay embedded canvas + cut the VNC hack

- Guest desktop (`jolt.microvm.desktop`) renders to the virtio-gpu DRM device
  instead of headless+VNC:
  - X11: `Xorg` with the modesetting driver on `/dev/dri/card0` (drop
    `x11vnc`); packages `xorg-server` + `xf86-video-modesetting`.
  - Wayland: `sway` with `WLR_BACKENDS=drm` on `/dev/dri/card0` (drop `wayvnc`).
- Bench presents frames through a `:vm-display`/`:gl-area` widget: register a
  glimmer-gtk canvas (via the existing `gtkx.clj` `register-widget!` seam) that
  uploads the current frame to a GL texture through `glimmer-gl` (git dep
  `jolt-lang/glimmer-gl`, `:gl-area` already registers into glimmer-gtk).
- Delete the vncviewer machinery from `vm.clj` (viewer-bin, view!, kill-viewer!,
  vnc-arg, auto-view?) — the embedded canvas replaces it.

### M3 — jolt-libkrun: bind the input path

- `krun_input_device_new` (config*, config_size, provider*, provider_size, err*)
  / `_new_from_fd` / `_destroy`.
- Build the two vtables in jolt:

```
krun_input_config (80 bytes):        u64 features@0, ptr userdata@8,
  ptr create@16, vtable[7]@24        ; destroy, query_device_name,
                                     ; query_serial_name, query_device_ids,
                                     ; query_event_capabilities, query_abs_info,
                                     ; query_properties
krun_input_event_provider (48 bytes): u64 features@0, ptr userdata@8,
  ptr create@16, vtable[3]@24        ; destroy, get_ready_efd, next_event
```

- Keyboard MVP: report `bustype=BUS_VIRTUAL(0x06)`, vendor `RH`, product 1,
  name "libkrun Virtual Keyboard" (copy the reference's values);
  `get_ready_efd` returns a jolt-owned eventfd, `next_event` drains a queue of
  Linux key events.
- Bench canvas forwards GTK key/mouse to the queue (mouse later; keyboard
  first). Then the desktop is fully usable without any external viewer.

### M4 — the UTM-like bench (chrome)

- Sidebar library (saved warps) + content pane (embedded display or breaker
  list) + inspector/toolbar — the three-pane product layout.
- Guided creation from a **registry tag list** (no ISOs): add a small
  `tags`/`_catalog` helper to `jolt-oci`, then the wizard = pick image:tag →
  pull → boot → profile decides what runs inside.
- Snapshot/stamp UI on top of the existing `vm/save-snapshot!`/
  `list-snapshots` (the VM machinery is already there).
- glimmer-cui remains the shelf candidate for a chrome swap once the canvas
  exists (its license needs checking before any port; glimmer-bui stays ruled
  out — GPL).

## Order of attack

M0 (extract, no deps) → M1 headless frame proof (the real risk) → M2 (cut the
hack, see it in the bench) → M3 (make it usable) → M4 (make it delightful).
M0+M1 are independent of the UI and each other; M2 depends on both.

## Repos touched

- `racklr-dev/jolt-framebuffer` (new, M0)
- `racklr-dev/jolt-libkrun` (M1, M3)
- `jolt-lang/glimmer-gl` (consumed, M2; already a git dep)
- `racklr-dev/jolt-microvm` (desktop.clj guest profiles, M2)
- `racklr-dev/jolt-oci` (tags/list helper, M4)
- `racklr-dev/hyperjay` (bench, M2 + M4)
