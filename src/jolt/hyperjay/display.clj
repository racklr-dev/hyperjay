;; The embedded display — the guest framebuffer drawn natively in the bench.
;;
;; A desktop thread's :display holds {:ip :display-path :width :height}. The
;; runner rewrites the P6 PPM at :display-path on every presented frame (atomic
;; tmp+rename), and this namespace turns it into a GL texture inside a
;; :gl-area widget. No VNC server, no gvncviewer, no RFB decode.
;;
;; The GL plumbing is deliberately tiny: one textured quad + one sampler. Each
;; :gl-area owns a GL state keyed by its widget pointer; realize! builds the
;; program/VAO/VBO/texture, tick! re-reads the PPM when the file changes,
;; render! uploads it and draws. GTK guarantees the GL context is current in
;; GtkGLArea's realize and render signals, so GL calls belong only there.
;;
;; The :gl-area handler props wire once at mount (glimmer-gl.gtk); each closure
;; captures its warp id, which is stable for the thread's life, so a cut!/
;; re-weave simply drops the widget and its tick callback stops polling.
(ns jolt.hyperjay.display
  (:require [jolt.framebuffer :as fb]
            [jolt.fs :as fs]
            [jolt.hyperjay.vm :as loom]
            [glimmer-gl.gtk :as gtk]
            [glimmer-gl.gl :as gl]
            [glimmer-gtk.ffi :as g]
            [glimmer-gtk.widget :as w]
            [jolt.ffi :as ffi]
            [clojure.string :as str]))

;; The host GL context may be desktop GL or GLES (GtkGLArea on this Xorg/NVIDIA
;; box gives OpenGL ES 3.2). Detect it and pick the matching GLSL: 330 core for
;; desktop, 300 es for GLES (the fragment stage must declare its precisions).
(def ^:private vs-core
  "#version 330 core
in vec2 a_pos;
in vec2 a_uv;
out vec2 v_uv;
void main(){ v_uv = a_uv; gl_Position = vec4(a_pos, 0.0, 1.0); }")

(def ^:private fs-core
  "#version 330 core
in vec2 v_uv;
out vec4 frag;
uniform sampler2D u_tex;
void main(){ frag = texture(u_tex, v_uv); }")

(def ^:private vs-es
  "#version 300 es
in vec2 a_pos;
in vec2 a_uv;
out vec2 v_uv;
void main(){ v_uv = a_uv; gl_Position = vec4(a_pos, 0.0, 1.0); }")

(def ^:private fs-es
  "#version 300 es
precision highp float;
precision highp sampler2D;
in vec2 v_uv;
out vec4 frag;
uniform sampler2D u_tex;
void main(){ frag = texture(u_tex, v_uv); }")

(defn- glsl
  "The (vs, fs) source pair for the current GL profile."
  []
  (let [v (gl/gl-get-string* gl/GL-VERSION)]
    (if (and v (str/includes? v "ES"))
      [vs-es fs-es]
      [vs-core fs-core])))

;; One quad as a triangle strip: 4 vertices x (x y u v). glTexImage2D places data
;; row 0 at texture v=0 (OpenGL's origin is bottom-left), so to draw the PPM's
;; top row (data row 0) at the top of the screen (clip y=+1) the top vertices
;; sample v=0 and the bottom vertices v=1.
(def ^:private quad-floats
  [-1.0  1.0  0.0 0.0
    1.0  1.0  1.0 0.0
   -1.0 -1.0  0.0 1.0
    1.0 -1.0  1.0 1.0])

(defonce ^:private states
  "area pointer -> {:prog :vao :vbo :tex
                    :frame (atom rgba-frame-or-nil)
                    :gen (atom N) :drawn (atom N) :sig (atom file-sig-or-nil)}.

  :sig is [mtime-ms size] of the last PPM read; tick! re-reads only when it
  changes. :gen bumps each newly decoded frame; render! uploads when :gen and
  :drawn differ. Entries are keyed by widget pointer and stay after unmount — a
  few-KB leak per woven display thread, fine for the bench."
  (atom {}))

(defn- frame-signature [path]
  (try
    (when (fs/exists? path)
      [(fs/file-time->millis (fs/last-modified-time path)) (fs/size path)])
    (catch Exception _ nil)))

(defn- read-frame [path]
  (try
    (fb/ppm->rgba (fs/read-all-bytes path))
    (catch Exception _ nil)))

(defn- upload! [st {:keys [width height bytes]}]
  (gl/gl-bind-texture gl/GL-TEXTURE-2D (:tex st))
  (let [n (alength bytes)
        ptr (ffi/alloc n)]
    (ffi/write-array ptr bytes)
    (gl/gl-tex-image-2d gl/GL-TEXTURE-2D 0 gl/GL-RGBA width height 0
                        gl/GL-RGBA gl/GL-UNSIGNED-BYTE ptr)
    (ffi/free ptr)))

(defn realize!
  "Build the display's GL objects. Context is current (GtkGLArea realize)."
  [area]
  (gtk/make-current area)
  (let [[vs fs] (glsl)
        prog (gl/make-program vs fs)]
    (when-not prog
      (throw (ex-info "display: shader link failed" {})))
    (let [vao   (gl/gen-one gl/gl-gen-vertex-arrays)
          vbo   (gl/gen-one gl/gl-gen-buffers)
          tex   (gl/gen-one gl/gl-gen-textures)
          a-pos (gl/gl-get-attrib-location prog "a_pos")
          a-uv  (gl/gl-get-attrib-location prog "a_uv")
          stride (* 4 (ffi/sizeof :float))]
      (gl/gl-bind-vertex-array vao)
      (gl/gl-bind-buffer gl/GL-ARRAY-BUFFER vbo)
      (let [ptr (gl/write-floats quad-floats)]
        (gl/gl-buffer-data gl/GL-ARRAY-BUFFER (* 16 (ffi/sizeof :float)) ptr gl/GL-STATIC-DRAW)
        (ffi/free ptr))
      (gl/gl-enable-vertex-attrib-array a-pos)
      (gl/gl-vertex-attrib-pointer a-pos 2 gl/GL-FLOAT gl/GL-FALSE stride 0)
      (gl/gl-enable-vertex-attrib-array a-uv)
      (gl/gl-vertex-attrib-pointer a-uv 2 gl/GL-FLOAT gl/GL-FALSE stride (* 2 (ffi/sizeof :float)))
      (gl/gl-bind-texture gl/GL-TEXTURE-2D tex)
      (gl/gl-tex-parameter-i gl/GL-TEXTURE-2D gl/GL-TEXTURE-MIN-FILTER gl/GL-LINEAR)
      (gl/gl-tex-parameter-i gl/GL-TEXTURE-2D gl/GL-TEXTURE-MAG-FILTER gl/GL-LINEAR)
      (gl/gl-tex-parameter-i gl/GL-TEXTURE-2D gl/GL-TEXTURE-WRAP-S gl/GL-CLAMP-TO-EDGE)
      (gl/gl-tex-parameter-i gl/GL-TEXTURE-2D gl/GL-TEXTURE-WRAP-T gl/GL-CLAMP-TO-EDGE)
      (gl/gl-use-program prog)
      (gl/gl-uniform-1i (gl/gl-get-uniform-location prog "u_tex") 0)
      (gl/gl-bind-vertex-array 0)
      (swap! states assoc area {:prog prog :vao vao :vbo vbo :tex tex
                                :frame (atom nil)
                                :gen (atom 0)
                                :drawn (atom 0)
                                :sig (atom nil)
                                :down (atom false)}))))

(defonce ^:private keyboard-attached? (atom false))

(defn ensure-keyboard!
  "Attach ONE GTK4 EventControllerKey to the toplevel root window — GtkGLArea
  can't hold keyboard focus — and forward key press/release to the focused warp
  thread, read at event time so a selection switch redirects input without
  re-attaching. Linux keycode = GTK hardware keycode - 8 (X keycodes sit eight
  above the evdev keycodes). Idempotent: the controller outlives the GLArea it
  was first attached from, so re-mounts across selection changes don't stack
  controllers. Called from the GLArea's realize handler."
  [area]
  (when-not @keyboard-attached?
    (let [root (gtk/gtk-widget-get-root area)]
      (when-not (ffi/null? root)
        (let [ctl (gtk/gtk-event-controller-key-new)
              kp (ffi/foreign-callable
                   (fn [_ _keyval kc _state _]
                     (let [code (- (long kc) 8)]
                       (when (pos? code) (loom/send-key! @loom/focus code true)))
                     0)
                   [:pointer :uint :uint :uint :pointer] :int :collect-safe)
              kr (ffi/foreign-callable
                   (fn [_ _keyval kc _state _]
                     (let [code (- (long kc) 8)]
                       (when (pos? code) (loom/send-key! @loom/focus code false))))
                   [:pointer :uint :uint :uint :pointer] :void :collect-safe)]
          (gtk/gtk-widget-add-controller root ctl)
          (w/retain-callable! kp)
          (w/retain-callable! kr)
          (g/g-signal-connect-data ctl "key-pressed" kp ffi/null ffi/null g/CONNECT-DEFAULT)
          (g/g-signal-connect-data ctl "key-released" kr ffi/null ffi/null g/CONNECT-DEFAULT)
          (reset! keyboard-attached? true))))))

(defn tick!
  "Poll the focused thread's PPM; when it changes, decode it and queue a
  re-render. Runs on the GTK frame clock — cheap until the file changes."
  [area]
  (let [st (get @states area)]
    (when st
      (when-let [path (get-in @loom/warp [@loom/focus :display :display-path])]
        (let [sig (frame-signature path)]
          (when (and sig (not= sig @(:sig st)))
            (reset! (:sig st) sig)
            (when-let [frame (read-frame path)]
              (reset! (:frame st) frame)
              (swap! (:gen st) inc)
              (gtk/queue-render area))))))))

(defn fit-viewport
  "Largest centered viewport that holds a fw x fh frame at its native aspect
  inside an aw x ah widget allocation. Returns [x y vw vh]; pillarboxes when
  the window is wider than the frame, letterboxes when taller. nil when any
  dimension is non-positive. Ported from jolt-libretro's GL window sink."
  [fw fh aw ah]
  (when (and (pos? fw) (pos? fh) (pos? aw) (pos? ah))
    (let [ar (/ (double fw) (double fh))
          win-ar (/ (double aw) (double ah))]
      (if (>= win-ar ar)
        (let [vh ah
              vw (max 1 (Math/round (* ah ar)))]
          [(quot (- aw vw) 2) 0 vw vh])
        (let [vw aw
              vh (max 1 (Math/round (/ aw ar)))]
          [0 (quot (- ah vh) 2) vw vh])))))

;; ---- mouse (the absolute touchscreen) ---------------------------------------
;; The guest's pointer is an absolute touchscreen sized to the frame, so a GTK
;; pointer event maps widget px -> frame px via the inverse of fit-viewport and
;; is forwarded as BTN_TOUCH + ABS_X/ABS_Y + SYN_REPORT through the loom to the
;; VM's .mouse file. The button-down state lives per GL area (in states) so a
;; drag emits position updates, a plain hover does not.
(def ^:private ev-key 1)
(def ^:private ev-abs 3)
(def ^:private ev-syn 0)
(def ^:private syn-report 0)
(def ^:private btn-touch 0x14a)
(def ^:private abs-x 0)
(def ^:private abs-y 1)

(defn- frame-size []
  (when-let [d (get-in @loom/warp [@loom/focus :display])]
    [(:width d) (:height d)]))

(defn unmap-pointer
  "Map a widget-relative pointer (wx,wy) to frame coordinates for a fw x fh
  frame letterboxed into an aw x ah widget (the inverse of fit-viewport).
  Clamped to the frame; nil when the pointer is outside the letterboxed
  viewport (the black bars are not part of the guest screen)."
  [fw fh aw ah wx wy]
  (when-let [[vx vy vw vh] (fit-viewport fw fh aw ah)]
    (when (and (<= vx wx (+ vx vw)) (<= vy wy (+ vy vh)))
      (let [fx (Math/round (* (/ (- wx vx) (double vw)) (double fw)))
            fy (Math/round (* (/ (- wy vy) (double vh)) (double fh)))]
        [(max 0 (min (dec fw) (long fx)))
         (max 0 (min (dec fh) (long fy)))]))))

(defn- emit-mouse! [type code value]
  (loom/send-mouse-event! @loom/focus {:type type :code code :value value}))

(defn button!
  "GTK GestureClick press/release -> touchscreen BTN_TOUCH + ABS_X/ABS_Y + SYN.
  A press also sets the per-area down flag so subsequent motion drags."
  [area _btn pressed? x y]
  (when-let [[fw fh] (frame-size)]
    (when-let [[fx fy] (unmap-pointer fw fh
                                     (gtk/widget-width area) (gtk/widget-height area)
                                     x y)]
      (when-let [st (get @states area)]
        (if pressed?
          (do (reset! (:down st) true)
              (emit-mouse! ev-key btn-touch 1)
              (emit-mouse! ev-abs abs-x fx)
              (emit-mouse! ev-abs abs-y fy)
              (emit-mouse! ev-syn syn-report 0))
          (when @(:down st)
            (reset! (:down st) false)
            (emit-mouse! ev-key btn-touch 0)
            (emit-mouse! ev-syn syn-report 0)))))))

(defn motion!
  "GTK motion -> ABS_X/ABS_Y + SYN while the button is held (a drag)."
  [area x y]
  (when-let [[fw fh] (frame-size)]
    (when-let [[fx fy] (unmap-pointer fw fh
                                     (gtk/widget-width area) (gtk/widget-height area)
                                     x y)]
      (when-let [st (get @states area)]
        (when @(:down st)
          (emit-mouse! ev-abs abs-x fx)
          (emit-mouse! ev-abs abs-y fy)
          (emit-mouse! ev-syn syn-report 0))))))

(defn render!
  "Upload the latest decoded frame (when new) and draw the textured quad into
  the largest centered viewport that preserves the frame's aspect; the rest of
  the widget stays black (letterbox/pillarbox). Context is current (GtkGLArea
  render)."
  [area]
  (let [st (get @states area)]
    (when st
      (let [w (max (long (gtk/widget-width area)) 1)
            h (max (long (gtk/widget-height area)) 1)
            frame @(:frame st)]
        (gl/gl-viewport 0 0 w h)
        (gl/gl-clear-color 0.0 0.0 0.0 1.0)
        (gl/gl-clear gl/GL-COLOR-BUFFER-BIT)
        (when frame
          (when (not= @(:drawn st) @(:gen st))
            (upload! st frame)
            (reset! (:drawn st) @(:gen st)))
          (let [[x y vw vh] (or (fit-viewport (:width frame) (:height frame) w h)
                                [0 0 w h])]
            (gl/gl-viewport x y vw vh)
            (gl/gl-active-texture gl/GL-TEXTURE0)
            (gl/gl-bind-texture gl/GL-TEXTURE-2D (:tex st))
            (gl/gl-use-program (:prog st))
            (gl/gl-bind-vertex-array (:vao st))
            (gl/gl-draw-arrays gl/GL-TRIANGLE-STRIP 0 4)
            (gl/gl-bind-vertex-array 0)))))))
