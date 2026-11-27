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
                                :sig (atom nil)}))))

(defn tick!
  "Poll the thread's PPM; when it changes, decode it and queue a re-render.
  Runs on the GTK frame clock — cheap until the file actually changes."
  [area warp-id]
  (let [st (get @states area)]
    (when st
      (when-let [path (get-in @loom/warp [warp-id :display :display-path])]
        (let [sig (frame-signature path)]
          (when (and sig (not= sig @(:sig st)))
            (reset! (:sig st) sig)
            (when-let [frame (read-frame path)]
              (reset! (:frame st) frame)
              (swap! (:gen st) inc)
              (gtk/queue-render area))))))))

(defn render!
  "Upload the latest decoded frame (when new) and draw the textured quad.
  Context is current (GtkGLArea render)."
  [area]
  (let [st (get @states area)]
    (when st
      (let [w (max (long (gtk/widget-width area)) 1)
            h (max (long (gtk/widget-height area)) 1)]
        (gl/gl-viewport 0 0 w h)
        (gl/gl-clear-color 0.0 0.0 0.0 1.0)
        (gl/gl-clear gl/GL-COLOR-BUFFER-BIT)
        (when-let [frame @(:frame st)]
          (when (not= @(:drawn st) @(:gen st))
            (upload! st frame)
            (reset! (:drawn st) @(:gen st)))
          (gl/gl-active-texture gl/GL-TEXTURE0)
          (gl/gl-bind-texture gl/GL-TEXTURE-2D (:tex st))
          (gl/gl-use-program (:prog st))
          (gl/gl-bind-vertex-array (:vao st))
          (gl/gl-draw-arrays gl/GL-TRIANGLE-STRIP 0 4)
          (gl/gl-bind-vertex-array 0))))))
