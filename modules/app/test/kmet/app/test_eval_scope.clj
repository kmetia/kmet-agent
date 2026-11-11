(ns kmet.app.test-eval-scope
  "Cwd-scoped eval library: the pinned cwd layer, the global layer, shadowing,
   scoped drops, moves, and the disk index across both roots."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :as t]
            [kmet.app.eval :as eval-service]
            [kmet.app.eval.files :as eval-files]
            [kmet.app.session.core :as session]
            [kmet.app.test-eval-support :as support]
            [kmet.app.tools.registry :as tools]
            [kmet.libs.edn-store :as edn-store]
            [kmet.test-tmp :as test-tmp]))

(defn- write-scoped!
  ([env scope name source] (write-scoped! env scope name source :defn))
  ([env scope name source kind]
   (eval-files/write-definition!
    (case scope :global (support/global-root env) :cwd (support/cwd-root env))
    {:name name :ns "user" :kind kind :source source})))

(defn- write-global!
  [env name source]
  (write-scoped! env :global name source))

(defn- cwd-root-for
  [env cwd]
  (str (fs/path (:agent-dir env) "eval-scopes" (session/encode-cwd cwd))))

(defn- entries-for
  [env name]
  (filter #(= name (:name %)) (support/eval-library env)))

(t/deftest cwd-scope-keys-are-distinct-for-similar-paths
  (let [env (support/eval-ctx)
        base (test-tmp/temp-dir! "eval-scope-key-")]
    (t/is (not= (cwd-root-for env (str base "/proj-a"))
                (cwd-root-for env (str base "/proj/a"))))
    (t/is (not= (cwd-root-for env (str base "/~x"))
                (cwd-root-for env (str base "/x"))))))

(t/deftest new-names-default-to-cwd-and-redefinitions-keep-their-scope
  (let [env (support/eval-ctx)
        cwd-file (support/library-file env :cwd "user" "f.clj")
        global-file (support/library-file env :global "user" "g.clj")]
    (support/eval! env "(defn f \"F.\" [] :one)")
    (t/is (fs/exists? cwd-file))
    (t/is (not (fs/exists? (support/library-file env :global "user" "f.clj"))))
    (t/is (= :cwd (:scope (first (entries-for env "user/f")))))
    (support/eval! env "(defn g \"G.\" [] :g)" :scope :global)
    (t/is (fs/exists? global-file))
    (t/is (= :global (:scope (first (entries-for env "user/g")))))
    (support/eval! env "(defn f \"F.\" [] :two)")
    (t/is (str/includes? (slurp cwd-file) ":two"))
    (support/eval! env "(defn g \"G.\" [] :gg)")
    (t/is (str/includes? (slurp global-file) ":gg"))
    (t/is (not (fs/exists? (support/library-file env :cwd "user" "g.clj"))))
    (t/is (= :global (:scope (first (entries-for env "user/g")))))
    (support/eval! env "(defn c \"C.\" [] :c)" :scope :cwd)
    (t/is (fs/exists? (support/library-file env :cwd "user" "c.clj")))
    (t/is (not (fs/exists? (support/library-file env :global "user" "c.clj"))))
    (t/is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"active in the cwd scope"
                            (support/eval! env "(defn f \"F.\" [] :three)"
                                           :scope :global)))
    (t/is (str/includes? (slurp cwd-file) ":two"))
    (t/is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"scope must be cwd or global"
                            (support/eval! env "(defn nope \"Nope.\" [] 1)" :scope :project)))))

(t/deftest cwd-shadows-global-and-the-library-reports-the-stack
  (let [env (support/eval-ctx)]
    (write-scoped! env :global "user/f" "(defn f \"F.\" [] :global)")
    (write-scoped! env :cwd "user/f" "(defn f \"F.\" [] :cwd)")
    (t/is (= ":cwd" (support/script-content (support/eval! env "(f)"))))
    (support/eval! env "(defn f \"F.\" [] :cwd-two)")
    (t/is (= ":cwd-two" (support/script-content (support/eval! env "(f)"))))
    (let [entries (entries-for env "user/f")
          by-scope (into {} (map (juxt :scope identity)) entries)]
      (t/is (= 2 (count entries)))
      (t/is (true? (:active (:cwd by-scope))))
      (t/is (false? (:active (:global by-scope))))
      (t/is (= (support/library-file env :cwd "user" "f.clj")
               (:file (:cwd by-scope))))
      (t/is (= (support/library-file env :global "user" "f.clj")
               (:file (:global by-scope)))))
    (t/is (= ":cwd"
             (:content (support/eval! env "(eval/describe 'f :scope)"))))
    (let [help (:content (support/eval! env "(eval/help :library)"))]
      (t/is (str/includes? help "user/f defn ([]) (cwd)"))
      (t/is (str/includes? help "user/f defn ([]) (global, shadowed)")))
    (let [restarted (support/restart-service env)]
      (t/is (= ":cwd-two"
               (support/script-content
                (support/eval-in! restarted (:session env)
                                  (:cwd env) "(f)")))))))

(t/deftest dropping-the-active-layer-restores-the-global-record
  (let [env (support/eval-ctx)]
    (write-scoped! env :global "user/f" "(defn f \"F.\" [] :global)")
    (write-scoped! env :cwd "user/f" "(defn f \"F.\" [] :cwd)")
    (t/is (= ":cwd" (support/script-content
                     (support/eval! env "(core/emit (f))"))))
    (let [result (support/eval! env nil :drop ["user/f"])]
      (t/is (not (:is-error result)) (:content result))
      (t/is (str/includes? (:content result) "[eval: forgot user/f (cwd)]")))
    (t/is (not (fs/exists? (support/library-file env :cwd "user" "f.clj"))))
    (t/is (fs/exists? (support/library-file env :global "user" "f.clj")))
    (t/is (= ":global" (:content (support/eval! env "(f)"))))
    (t/is (= [{:name "user/f" :ns "user" :kind :defn :doc "F." :arglists '([])
               :file (support/library-file env :global "user" "f.clj")
               :scope :global :active true}]
             (support/eval-library env)))))

(t/deftest a-scoped-drop-of-the-active-layer-restores-the-next
  (let [env (support/eval-ctx)]
    (write-scoped! env :global "user/f" "(defn f \"F.\" [] :global)")
    (write-scoped! env :cwd "user/f" "(defn f \"F.\" [] :cwd)")
    (t/is (= ":cwd" (support/script-content
                     (support/eval! env "(core/emit (f))"))))
    (let [result (support/eval! env nil :drop ["user/f"] :scope :cwd)]
      (t/is (not (:is-error result)) (:content result))
      (t/is (str/includes? (:content result) "[eval: forgot user/f (cwd)]")))
    (t/is (= ":global" (:content (support/eval! env "(f)"))))
    (t/is (= [:global] (mapv :scope (entries-for env "user/f"))))))

(t/deftest a-scoped-forget-of-a-shadowed-layer-leaves-the-active-live
  (let [env (support/eval-ctx)]
    (write-scoped! env :global "user/f" "(defn f \"F.\" [] :global)")
    (write-scoped! env :cwd "user/f" "(defn f \"F.\" [] :cwd)")
    (t/is (= ":cwd" (support/script-content
                     (support/eval! env "(core/emit (f))"))))
    (let [result (support/eval! env "(eval/forget 'user/f)" :scope :global)]
      (t/is (not (:is-error result)) (:content result))
      (t/is (str/includes? (:content result) "[eval: forgot user/f (global)]")))
    (t/is (= ":cwd" (:content (support/eval! env "(f)"))))
    (t/is (not (fs/exists? (support/library-file env :global "user" "f.clj"))))
    (t/is (fs/exists? (support/library-file env :cwd "user" "f.clj")))))

(t/deftest dropping-a-shadowed-layer-leaves-the-active-definition-live
  (let [env (support/eval-ctx)]
    (write-scoped! env :global "user/f" "(defn f \"F.\" [] :global)")
    (write-scoped! env :cwd "user/f" "(defn f \"F.\" [] :cwd)")
    (t/is (= ":cwd" (support/script-content
                     (support/eval! env "(core/emit (f))"))))
    (let [result (support/eval! env nil :drop ["user/f"] :scope :global)]
      (t/is (not (:is-error result)) (:content result))
      (t/is (str/includes? (:content result) "[eval: forgot user/f (global)]")))
    (t/is (not (fs/exists? (support/library-file env :global "user" "f.clj"))))
    (t/is (fs/exists? (support/library-file env :cwd "user" "f.clj")))
    (t/is (= ":cwd" (:content (support/eval! env "(f)"))))
    (t/is (= [:cwd] (mapv :scope (entries-for env "user/f"))))))

(t/deftest a-scoped-drop-without-an-environment-never-builds-one
  (let [env (support/eval-ctx)
        global (support/library-file env :global "user" "f.clj")
        cwd (support/library-file env :cwd "user" "f.clj")]
    (write-global! env "user/f" "(defn f \"F.\" [] :global)")
    (fs/create-dirs (fs/parent cwd))
    (spit cwd "(ns user)\n(defn f \"F.\" [] :cwd)\n")
    (let [result (with-redefs-fn {#'eval-service/build-environment
                                  (fn [& _]
                                    (throw (ex-info "must not build" {:type :test})))}
                   #(support/eval! env nil :drop ["user/f"]))]
      (t/is (not (:is-error result)) (:content result))
      (t/is (str/includes? (:content result) "[eval: forgot user/f (cwd)]")))
    (t/is (not (fs/exists? cwd)))
    (t/is (fs/exists? global)))
  (let [env (support/eval-ctx)
        global (support/library-file env :global "user" "f.clj")
        cwd (support/library-file env :cwd "user" "f.clj")]
    (write-global! env "user/f" "(defn f \"F.\" [] :global)")
    (fs/create-dirs (fs/parent cwd))
    (spit cwd "(ns user)\n(defn f \"F.\" [] :cwd)\n")
    (let [result (support/eval! env nil :drop ["user/f"] :scope :global)]
      (t/is (not (:is-error result)) (:content result))
      (t/is (str/includes? (:content result) "[eval: forgot user/f (global)]")))
    (t/is (not (fs/exists? global)))
    (t/is (fs/exists? cwd))))

(t/deftest the-eval-verbs-move-and-forget
  (let [env (support/eval-ctx)
        global (support/library-file env :global "user" "f.clj")
        cwd (support/library-file env :cwd "user" "f.clj")]
    (write-scoped! env :cwd "user/f" "(defn f \"F.\" [] :cwd)")
    (let [moved (support/eval! env "(eval/move 'user/f :global)")]
      (t/is (not (:is-error moved)) (:content moved))
      (t/is (str/includes? (:content moved) "[eval: moved user/f")))
    (t/is (fs/exists? global))
    (t/is (not (fs/exists? cwd)))
    (let [forgot (support/eval! env "(eval/forget 'user/f)")]
      (t/is (not (:is-error forgot)) (:content forgot))
      (t/is (str/includes? (:content forgot) "[eval: forgot user/f (global)]")))
    (t/is (not (fs/exists? global)))))

(t/deftest an-invalid-scope-fails-before-the-environment-is-built
  (let [env (support/eval-ctx)]
    (with-redefs-fn {#'eval-service/build-environment
                     (fn [& _] (throw (ex-info "must not build" {:type :test})))}
      #(t/is (thrown-with-msg? clojure.lang.ExceptionInfo
                               #"scope must be cwd or global"
                               (support/eval! env "(def x 1)" :scope :project))))))

(t/deftest the-cwd-scope-is-pinned-to-the-first-build
  (let [env (support/eval-ctx)
        cwd-a (:cwd env)
        cwd-b (test-tmp/temp-dir! "eval-pinned-other-")
        result (do (support/eval! env "(def pinned \"Pinned.\" 1)")
                   (support/eval-in! (:service env) (:session env) cwd-b
                                     "(spit \"marker.txt\" \"b\") (def later \"Later.\" 2) (core/cwd)"))
        a-root (cwd-root-for env cwd-a)
        b-root (cwd-root-for env cwd-b)]
    (t/is (str/starts-with? (:content result) cwd-b))
    (t/is (= "b" (slurp (str (fs/path cwd-b "marker.txt"))))
          "the call's file verbs still use the later cwd")
    (t/is (fs/exists? (str (fs/path a-root "user" "later.clj"))))
    (t/is (not (fs/exists? (str (fs/path b-root "user" "later.clj"))))
          "the library root stays on the build-time cwd")
    (t/is (= cwd-b
             (:content (support/eval-in! (:service env) (:session env) cwd-b
                                         "(core/cwd)"))))
    (let [index ((:index (:service env)) {:session (:session env) :cwd cwd-b})]
      (t/is (= (str (fs/path a-root "user" "later.clj"))
               (:file (first (filter #(= "user/later" (:name %))
                                     (:entries index)))))
            "the index follows the built environment's pinned roots"))))

(t/deftest a-broken-cwd-shadow-leaves-the-global-record-active
  (let [env (support/eval-ctx)
        cwd (support/library-file env :cwd "user" "f.clj")]
    (write-global! env "user/f" "(defn f \"F.\" [] :global)")
    (fs/create-dirs (fs/parent cwd))
    (spit cwd "(ns user)\n(throw (ex-info \"boom\" {}))\n")
    (let [result (support/eval! env "(core/emit (f))")]
      (t/is (not (:is-error result)) (:content result))
      (t/is (= ":global" (support/script-content result)))
      (t/is (= :cwd (get-in result [:details :failures 0 :scope])))
      (t/is (= 1 (count (entries-for env "user/f")))))
    (t/is (= ":global"
             (:content (support/eval! env "(eval/describe 'f :scope)"))))))

(t/deftest a-broken-global-file-leaves-the-cwd-record-active
  (let [env (support/eval-ctx)
        global (support/library-file env :global "user" "f.clj")]
    (write-scoped! env :cwd "user/f" "(defn f \"F.\" [] :cwd)")
    (fs/create-dirs (fs/parent global))
    (spit global "(ns user)\n(throw (ex-info \"boom\" {}))\n")
    (let [result (support/eval! env "(core/emit (f))")
          failures (get-in result [:details :failures])]
      (t/is (not (:is-error result)) (:content result))
      (t/is (= ":cwd" (support/script-content result)))
      (t/is (= :global (:scope (first failures))))
      (t/is (= [:cwd] (mapv :scope (entries-for env "user/f")))))))

(t/deftest a-method-shadow-is-restored-by-a-drop
  (let [env (support/eval-ctx)]
    (write-scoped! env :global "user/area" "(defmulti area :shape)" :defmulti)
    (write-scoped! env :global "user/area :rect"
                   "(defmethod area :rect [m] :global)" :defmethod)
    (write-scoped! env :cwd "user/area :rect"
                   "(defmethod area :rect [m] :cwd)" :defmethod)
    (t/is (= ":cwd" (support/script-content
                     (support/eval! env "(area {:shape :rect})"))))
    (let [result (support/eval! env nil :drop ["user/area :rect"])]
      (t/is (not (:is-error result)) (:content result)))
    (t/is (= ":global" (:content (support/eval! env "(area {:shape :rect})"))))
    (t/is (= ":global"
             (:content (support/eval! env
                                      "(eval/describe (symbol \"user/area :rect\") :scope)"))))))

(t/deftest patch-and-edit-form-refuse-a-non-active-layer
  (let [env (support/eval-ctx)]
    (write-scoped! env :global "user/f" "(defn f \"F.\" [] :global)")
    (write-scoped! env :cwd "user/f" "(defn f \"F.\" [] :cwd)")
    (t/is (= ":cwd" (support/script-content
                     (support/eval! env "(core/emit (f))"))))
    (let [patch (support/eval! env "(eval/patch 'user/f :cwd :other)" :scope :global)]
      (t/is (:is-error patch))
      (t/is (str/includes? (:content patch) "active in the cwd scope"))
      (t/is (str/includes? (slurp (support/library-file env :global "user" "f.clj"))
                           ":global")))
    (let [edit (support/eval! env
                              "(eval/edit-form 'user/f '(defn f \"F.\" [] :edited))"
                              :scope :global)]
      (t/is (:is-error edit))
      (t/is (str/includes? (:content edit) "active in the cwd scope")))
    (t/is (not (:is-error (support/eval! env "(eval/patch 'user/f :cwd :patched)"))))
    (t/is (str/includes? (slurp (support/library-file env :cwd "user" "f.clj"))
                         ":patched"))))

(t/deftest move-flips-the-layer-without-re-evaluating
  (let [env (support/eval-ctx)]
    (support/eval! env "(defn f \"F.\" [] :one)")
    (let [result (support/eval! env "(eval/move 'user/f :global)")]
      (t/is (not (:is-error result)) (:content result))
      (t/is (str/includes? (:content result) "[eval: moved user/f (cwd → global)]")))
    (t/is (not (fs/exists? (support/library-file env :cwd "user" "f.clj"))))
    (t/is (fs/exists? (support/library-file env :global "user" "f.clj")))
    (t/is (= ":one" (support/script-content (support/eval! env "(f)"))))
    (t/is (= ":global"
             (:content (support/eval! env "(eval/describe 'f :scope)"))))
    (let [restarted (support/restart-service env)]
      (t/is (= ":one"
               (support/script-content
                (support/eval-in! restarted (:session env) (:cwd env)
                                  "(f)")))))
    (let [result (support/eval! env "(eval/move 'user/f :cwd)")]
      (t/is (not (:is-error result)) (:content result)))
    (t/is (fs/exists? (support/library-file env :cwd "user" "f.clj")))
    (t/is (not (fs/exists? (support/library-file env :global "user" "f.clj"))))
    (let [same (support/eval! env "(eval/move 'user/f :cwd)")]
      (t/is (:is-error same))
      (t/is (str/includes? (:content same) "already in the cwd scope")))
    (let [invalid (support/eval! env "(eval/move 'user/f :elsewhere)")]
      (t/is (:is-error invalid))
      (t/is (str/includes? (:content invalid) "scope must be cwd or global")))))

(t/deftest the-move-service-works-on-a-built-environment
  (let [env (support/eval-ctx)]
    (support/eval! env "(defn f \"F.\" [] :one)")
    (let [result ((:move! (:service env)) {:session (:session env)
                                           :name "user/f"
                                           :scope "global"
                                           :cwd (:cwd env)})]
      (t/is (not (:is-error result)) (:content result))
      (t/is (str/includes? (:content result) "[eval: moved user/f (cwd → global)]")))
    (t/is (fs/exists? (support/library-file env :global "user" "f.clj")))
    (t/is (not (fs/exists? (support/library-file env :cwd "user" "f.clj"))))
    (t/is (= ":one" (:content (support/eval! env "(f)"))))
    (t/is (= ":global"
             (:content (support/eval! env "(eval/describe 'f :scope)"))))))

(t/deftest move-refuses-collisions-sources-and-unwritten-definitions
  (let [env (support/eval-ctx)]
    (write-scoped! env :global "user/c" "(defn c [] :global)")
    (write-scoped! env :cwd "user/c" "(defn c [] :cwd)")
    (t/is (= ":cwd" (support/script-content
                     (support/eval! env "(core/emit (c))"))))
    (let [result (support/eval! env "(eval/move 'user/c :global)")]
      (t/is (:is-error result))
      (t/is (str/includes? (:content result) "already has user/c")))
    (t/is (str/includes? (slurp (support/library-file env :global "user" "c.clj"))
                         ":global"))
    (t/is (str/includes? (slurp (support/library-file env :cwd "user" "c.clj"))
                         ":cwd")))
  (let [env (support/eval-ctx)]
    (tools/register-eval-source! (:tools env) :license
                                 (fn [_] {:ns {'ext.license {'value 1}}})
                                 {:owner [:test :eval-scope :license]})
    (support/eval! env "(ns ext.license) value")
    (let [result (support/eval! env "(eval/move 'ext.license/value :global)")]
      (t/is (:is-error result))
      (t/is (str/includes? (:content result) "source-provided"))))
  (let [env (support/eval-ctx)]
    (with-redefs [eval-files/write-definition!
                  (fn [& _] (throw (ex-info "disk full" {})))]
      (support/eval! env "(defn lost \"Lost.\" [] 1)"))
    (let [result (support/eval! env "(eval/move 'user/lost :global)")]
      (t/is (:is-error result))
      (t/is (str/includes? (:content result) "not written")))))

(t/deftest a-no-environment-drop-of-a-global-only-file-reports-global
  (let [env (support/eval-ctx)
        global (support/library-file env :global "user" "f.clj")]
    (write-scoped! env :global "user/f" "(defn f \"F.\" [] :global)")
    (let [result (support/eval! env nil :drop ["user/f"])]
      (t/is (not (:is-error result)) (:content result))
      (t/is (str/includes? (:content result) "[eval: forgot user/f (global)]")))
    (t/is (not (fs/exists? global)))))

(t/deftest a-scoped-drop-can-remove-a-broken-file
  (let [env (support/eval-ctx)
        global (support/library-file env :global "user" "f.clj")]
    (write-scoped! env :cwd "user/f" "(defn f \"F.\" [] :cwd)")
    (fs/create-dirs (fs/parent global))
    (spit global "(ns user)\n(throw (ex-info \"boom\" {}))\n")
    (t/is (= ":cwd" (support/script-content (support/eval! env "(f)"))))
    (let [result (support/eval! env nil :drop ["user/f"] :scope :global)]
      (t/is (not (:is-error result)) (:content result))
      (t/is (str/includes? (:content result) "[eval: forgot user/f (global)]")))
    (t/is (not (fs/exists? global)))
    (t/is (fs/exists? (support/library-file env :cwd "user" "f.clj")))))

(t/deftest move-refuses-a-target-file-that-was-not-loaded
  (let [env (support/eval-ctx)
        global (support/library-file env :global "user" "f.clj")]
    (support/eval! env "(defn f \"F.\" [] :cwd)")
    (fs/create-dirs (fs/parent global))
    (spit global "(ns user)\n(defn f \"F.\" [] :global)\n")
    (let [result (support/eval! env "(eval/move 'user/f :global)")]
      (t/is (:is-error result))
      (t/is (str/includes? (:content result) "already exists")))
    (t/is (fs/exists? (support/library-file env :cwd "user" "f.clj")))
    (t/is (fs/exists? global))))

(t/deftest patch-writes-the-active-global-layer
  (let [env (support/eval-ctx)
        global (support/library-file env :global "user" "f.clj")]
    (support/eval! env "(defn f \"F.\" [] :global)" :scope :global)
    (t/is (not (:is-error (support/eval! env "(eval/patch 'user/f :global :patched)"))))
    (t/is (str/includes? (slurp global) ":patched"))
    (t/is (not (fs/exists? (support/library-file env :cwd "user" "f.clj"))))
    (let [refused (support/eval! env "(eval/patch 'user/f :patched :again)"
                                 :scope :cwd)]
      (t/is (:is-error refused))
      (t/is (str/includes? (:content refused) "active in the global scope")))
    (t/is (str/includes? (slurp global) ":patched"))))

(t/deftest move-refuses-a-file-that-defines-several-addresses
  (let [env (support/eval-ctx)
        cwd (support/library-file env :cwd "user" "pair.clj")]
    (fs/create-dirs (fs/parent cwd))
    (spit cwd ";; kmet eval: written by the eval tool\n(ns user)\n(def a 1)\n(def b 2)\n")
    (t/is (= "3" (support/script-content
                  (support/eval! env "(core/emit (+ a b))"))))
    (let [result (support/eval! env "(eval/move 'user/a :global)")]
      (t/is (:is-error result))
      (t/is (str/includes? (:content result) "also defines user/b")))
    (t/is (fs/exists? cwd))
    (t/is (not (fs/exists? (support/library-file env :global "user" "pair.clj"))))
    (let [restarted (support/restart-service env)]
      (t/is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"also defines user/b"
                              ((:move! restarted) {:session (:session env)
                                                   :name "user/a"
                                                   :scope "global"
                                                   :cwd (:cwd env)}))))
    (t/is (fs/exists? cwd))
    (t/is (not (fs/exists? (support/library-file env :global "user" "pair.clj"))))))

(t/deftest move-refuses-a-multi-name-declaration
  (let [env (support/eval-ctx)]
    (support/eval! env "(declare ^{:doc \"Early.\"} early ^{:doc \"Late.\"} late)")
    (let [result (support/eval! env "(eval/move 'user/early :global)")]
      (t/is (:is-error result))
      (t/is (str/includes? (:content result) "also defines user/late")))
    (t/is (fs/exists? (support/library-file env :cwd "user" "early.clj")))
    (t/is (not (fs/exists? (support/library-file env :global "user" "early.clj"))))))

(t/deftest the-disk-index-marks-the-shadowed-layer
  (let [env (support/eval-ctx)
        roots {:global (support/global-root env) :cwd (support/cwd-root env)}
        cwd (support/library-file env :cwd "user" "f.clj")
        global (support/library-file env :global "user" "f.clj")]
    (write-global! env "user/f" "(defn f \"F.\" [] :global)")
    (fs/create-dirs (fs/parent cwd))
    (spit cwd "(ns user)\n(defn f \"F.\" [] :cwd)\n")
    (let [index (eval-files/index roots)
          by-scope (into {} (map (juxt :scope identity) (:entries index)))]
      (t/is (= 2 (count (:entries index))))
      (t/is (true? (:active (:cwd by-scope))))
      (t/is (false? (:active (:global by-scope))))
      (t/is (= global (:file (:global by-scope))))
      (t/is (= cwd (:file (:cwd by-scope)))))))

(t/deftest cwd-scopes-separate-worktrees-and-subdirectories
  (let [env (support/eval-ctx)
        service (:service env)
        cwd-a (:cwd env)
        cwd-b (test-tmp/temp-dir! "eval-worktree-")
        session-a (:session env)
        session-b (session/create-session (test-tmp/temp-dir! "eval-worktree-session-")
                                          {:cwd cwd-b})]
    (support/eval-in! service session-a cwd-a "(def only-a \"Only A.\" 1)")
    (support/eval-in! service session-b cwd-b "(def only-b \"Only B.\" 2)")
    (t/is (fs/exists? (str (fs/path (cwd-root-for env cwd-a) "user" "only-a.clj"))))
    (t/is (fs/exists? (str (fs/path (cwd-root-for env cwd-b) "user" "only-b.clj"))))
    (t/is (= "false"
             (:content (support/eval-in! service session-b cwd-b
                                         "(core/emit (some? (resolve 'only-a)))"))))
    (let [restarted (support/restart-service env)
          session-c (session/create-session (test-tmp/temp-dir! "eval-worktree-session-")
                                            {:cwd cwd-b})]
      (t/is (= "true"
               (support/script-content
                (support/eval-in! restarted session-c cwd-b
                                  "(core/emit (some? (resolve 'only-b)))"))))
      (t/is (= "false"
               (:content (support/eval-in! restarted session-c cwd-b
                                           "(core/emit (some? (resolve 'only-a)))")))))))

(t/deftest move-file-publication-rolls-back-and-locks-in-fixed-order
  (let [env (support/eval-ctx)
        roots {:global (support/global-root env) :cwd (support/cwd-root env)}
        record (eval-files/write-definition!
                (:cwd roots)
                {:name "user/m" :ns "user" :kind :defn
                 :source "(defn m [] :m)"})
        record {:name "user/m" :ns "user" :kind :defn
                :source "(defn m [] :m)" :file record :scope :cwd}
        global-lock (str (fs/path (:global roots) ".lock"))
        cwd-lock (str (fs/path (:cwd roots) ".lock"))
        order (atom [])
        delete fs/delete-if-exists
        lock edn-store/with-file-lock]
    (with-redefs [edn-store/with-file-lock
                  (fn [path f]
                    (swap! order conj path)
                    (lock path f))]
      (t/is (thrown? clojure.lang.ExceptionInfo
                     (with-redefs [fs/delete-if-exists
                                   (fn [path]
                                     (if (= (str path) (:file record))
                                       (throw (ex-info "delete failed" {}))
                                       (delete path)))]
                       (eval-files/move-definition! roots record :global))))
      (t/is (= [global-lock cwd-lock] @order)))
    (t/is (not (fs/exists? (eval-files/definition-path (:global roots) record))))
    (t/is (fs/exists? (:file record)))
    (reset! order [])
    (with-redefs [edn-store/with-file-lock
                  (fn [path f]
                    (swap! order conj path)
                    (lock path f))]
      (t/is (string? (eval-files/move-definition! roots record :global))))
    (t/is (= [global-lock cwd-lock] @order))))
