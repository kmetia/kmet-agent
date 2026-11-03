(ns kmet.app.test-permissions
  "The `:permissions` policy and the `:approvals` delivery seam: what a verdict
  is, what a broken provider does (deny, never allow), when the approver is
  consulted, and where the gate sits relative to the extension hooks."
  (:require [clojure.test :refer [deftest is testing]]
            [kmet.app.hooks :as hooks]
            [kmet.app.permissions :as permissions]
            [kmet.app.tools.invoke :as invoke]
            [kmet.srce.core :as srce]))

(defn- with-policy
  "A root ctx carrying POLICY as `:permissions`."
  [policy]
  (let [root (srce/root)]
    (srce/provide! root :permissions (permissions/policy policy))
    root))

(def bash-call {:tool-name "bash" :args {"command" "ls"}})

(deftest a-ctx-without-a-policy-allows
  (is (nil? (permissions/check (srce/root) bash-call))
      "the gate exists only where an app mounted a policy"))

(deftest a-deny-becomes-the-hook-result-the-loop-settles-with
  (let [ctx (with-policy (fn [_ _] {:decision :deny :reason "read-only workspace"}))]
    (is (= {:block true :reason "read-only workspace"} (permissions/check ctx bash-call))))
  (let [ctx (with-policy (fn [_ _] :deny))]
    (is (= {:block true :reason "denied by the permission policy"}
           (permissions/check ctx bash-call))
        "a bare :deny still names itself")))

(deftest a-broken-policy-fails-closed
  (testing "a policy that throws denies, with the error as the reason"
    (let [ctx (with-policy (fn [_ _] (throw (ex-info "boom" {}))))]
      (is (= "permission policy error: boom" (:reason (permissions/check ctx bash-call))))))
  (testing "an answer that is not a verdict denies"
    (let [ctx (with-policy (fn [_ _] :maybe))]
      (is (re-find #":maybe" (:reason (permissions/check ctx bash-call)))))))

(deftest an-ask-with-nobody-to-ask-is-a-deny
  (let [ctx (with-policy (fn [_ _] :ask))]
    (is (= "no approval channel is mounted — this run cannot ask"
           (:reason (permissions/check ctx bash-call))))))

(deftest the-approver-answers-and-a-scope-is-remembered
  (let [remembered (atom [])
        asked (atom 0)
        root (srce/root)]
    (srce/provide! root :permissions
                   (permissions/policy (fn [_ _] :ask)
                                       (fn [_ctx request scope]
                                         (swap! remembered conj [(:tool-name request) scope]))))
    (srce/provide! root :approvals
                   (permissions/approver
                    (fn [_ctx request]
                      (swap! asked inc)
                      (is (= "bash" (:tool-name request)) "the approver sees the call")
                      {:decision :allow :scope :agent})))
    (is (nil? (permissions/check root bash-call)) "an approved ask proceeds")
    (is (= 1 @asked) "the approver was consulted")
    (is (= [["bash" :agent]] @remembered) "and its scope is recorded")))

(deftest an-invented-scope-is-honored-but-not-remembered
  (let [remembered (atom [])
        root (srce/root)]
    (srce/provide! root :permissions
                   (permissions/policy (fn [_ _] :ask)
                                       (fn [_ _ scope] (swap! remembered conj scope))))
    (srce/provide! root :approvals
                   (permissions/approver (fn [_ _] {:decision :allow :scope :forever})))
    (is (nil? (permissions/check root bash-call)) "the answer still allows")
    (is (empty? @remembered) "but :forever is not a scope")))

(deftest the-run-signal-rides-the-request
  ;; both providers get the same request, and the run's signal is on it, so a
  ;; dialog can abandon a question whose run was cancelled
  (let [signal (atom false)
        seen (atom [])
        root (srce/root)]
    (srce/provide! root :permissions
                   (permissions/policy (fn [_ request] (swap! seen conj request) :ask)))
    (srce/provide! root :approvals
                   (permissions/approver (fn [_ request]
                                           (swap! seen conj request)
                                           {:decision :deny})))
    (permissions/check root (assoc bash-call :signal signal))
    (is (= 2 (count @seen)))
    (is (every? #(identical? signal (:signal %)) @seen)
        "the policy and the approver both see the run's signal")))

(deftest the-approver-sees-the-policys-reason
  ;; the dialog can say *why* it is asking, not just what is being asked
  (let [asked (atom nil)
        root (srce/root)]
    (srce/provide! root :permissions
                   (permissions/policy (fn [_ _] {:decision :ask
                                                  :reason "writes outside the workspace"})))
    (srce/provide! root :approvals
                   (permissions/approver (fn [_ request]
                                           (reset! asked request)
                                           {:decision :deny})))
    (permissions/check root bash-call)
    (is (= "writes outside the workspace" (:reason @asked)))))

(deftest a-denied-approval-blocks
  (let [root (srce/root)]
    (srce/provide! root :permissions (permissions/policy (fn [_ _] :ask)))
    (srce/provide! root :approvals
                   (permissions/approver (fn [_ _] {:decision :deny :reason "not now"})))
    (is (= {:block true :reason "not now"} (permissions/check root bash-call)))))

(deftest a-remember-that-throws-does-not-lose-the-approval
  (let [root (srce/root)]
    (srce/provide! root :permissions
                   (permissions/policy (fn [_ _] :ask)
                                       (fn [_ _ _] (throw (ex-info "no store" {})))))
    (srce/provide! root :approvals
                   (permissions/approver (fn [_ _] {:decision :allow :scope :once})))
    (is (nil? (permissions/check root bash-call))
        "the human said yes; bookkeeping is not the decision")))

(deftest a-broken-approver-fails-closed
  (let [root (srce/root)]
    (srce/provide! root :permissions (permissions/policy (fn [_ _] :ask)))
    (srce/provide! root :approvals
                   (permissions/approver (fn [_ _] (throw (ex-info "dialog died" {})))))
    (is (= "approval request failed: dialog died"
           (:reason (permissions/check root bash-call))))))

(deftest two-agent-ctxs-decide-independently
  (let [root (srce/root)
        allowed (srce/extend root {})
        denied (srce/extend root {})]
    (srce/provide! allowed :permissions (permissions/policy (fn [_ _] :allow)))
    (srce/provide! denied :permissions (permissions/policy (fn [_ _] :deny)))
    (is (nil? (permissions/check allowed bash-call)))
    (is (= {:block true :reason "denied by the permission policy"}
           (permissions/check denied bash-call)))
    (is (nil? (permissions/check root bash-call))
        "the shared root mounted no policy, so neither agent inherits one")))

(deftest a-child-cannot-shadow-the-roots-policy
  ;; One provider per name per realm path (srce.md §3): a per-agent policy
  ;; therefore mounts where nothing above provides one, which is why
  ;; :bundle/minimal carries :approvals and not :permissions.
  (let [root (with-policy (fn [_ _] :allow))
        child (srce/extend root {})]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"already provided"
                          (srce/provide! child :permissions
                                         (permissions/policy (fn [_ _] :deny)))))
    (is (nil? (permissions/check child bash-call))
        "the child reads the ancestor's policy")))

;; ── remembering an answer ─────────────────────────────────────────────────

(defn- gated
  "A root mounting a remembering policy, with APPROVE answering the ask. The
   memory is the caller's to place — on the root for a process-wide test, on an
   agent's ctx when the scope is the thing under test."
  [approve]
  (let [root (srce/root)]
    (srce/provide! root :permissions (permissions/remembering (fn [_ _] :ask)))
    (srce/provide! root :approvals (permissions/approver approve))
    root))

(defn- asked-count
  "A counting approver answering SCOPE, and the atom it counts in."
  [scope]
  (let [asked (atom 0)]
    [asked (fn [_ _] (swap! asked inc) {:decision :allow :scope scope})]))

(deftest a-remembered-answer-is-not-asked-again
  (let [[asked approve] (asked-count :agent)
        root (gated approve)]
    (srce/provide! root permissions/memory-key (permissions/memory (atom #{})))
    (is (nil? (permissions/check root bash-call)) "the first ask is answered")
    (is (nil? (permissions/check root bash-call))
        "the second is allowed from the memory, without a question")
    (is (= 1 @asked) "so the human was asked once")
    (is (nil? (permissions/check root (assoc bash-call :tool-name "edit"))))
    (is (= 2 @asked) "a different tool is a different question")))

(deftest once-remembers-nothing
  (let [[asked approve] (asked-count :once)
        root (gated approve)]
    (srce/provide! root permissions/memory-key (permissions/memory (atom #{})))
    (is (nil? (permissions/check root bash-call)))
    (is (nil? (permissions/check root bash-call)))
    (is (= 2 @asked) "a :once answer is the call's, not the tool's")))

(deftest an-agent-scope-stays-with-its-agent
  (let [[asked approve] (asked-count :agent)
        workspace (atom #{})
        root (gated approve)
        a (srce/extend root {:name :agent-a})
        b (srce/extend root {:name :agent-b})]
    (srce/provide! a permissions/memory-key (permissions/memory workspace))
    (srce/provide! b permissions/memory-key (permissions/memory workspace))
    (is (nil? (permissions/check a bash-call)))
    (is (nil? (permissions/check a bash-call)) "agent a remembers its own answer")
    (is (= 1 @asked))
    (is (nil? (permissions/check b bash-call))
        "and agent b is allowed too — but only because it was asked")
    (is (= 2 @asked) "one question per agent")))

(deftest a-workspace-scope-is-shared
  (let [[asked approve] (asked-count :workspace)
        workspace (atom #{})
        root (gated approve)
        a (srce/extend root {:name :agent-a})
        b (srce/extend root {:name :agent-b})]
    (srce/provide! a permissions/memory-key (permissions/memory workspace))
    (srce/provide! b permissions/memory-key (permissions/memory workspace))
    (is (nil? (permissions/check a bash-call)))
    (is (nil? (permissions/check b bash-call)))
    (is (= 1 @asked) "one answer covers the workspace")))

(deftest a-remembered-allow-does-not-override-a-deny
  ;; The memory answers the question the policy asked; it is not a second policy
  ;; that can turn a deny into an allow.
  (let [asked (atom 0)
        root (srce/root)]
    (srce/provide! root :permissions
                   (permissions/remembering
                    (fn [_ {:keys [tool-name]}]
                      (if (= "bash" tool-name) :ask :deny))))
    (srce/provide! root :approvals
                   (permissions/approver
                    (fn [_ _] (swap! asked inc) {:decision :allow :scope :workspace})))
    (srce/provide! root permissions/memory-key (permissions/memory (atom #{})))
    (is (nil? (permissions/check root bash-call)))
    (is (= 1 @asked))
    (is (nil? (permissions/check root bash-call)) "remembered: the policy still asks, the memory answers")
    (is (= {:block true :reason "denied by the permission policy"}
           (permissions/check root (assoc bash-call :tool-name "edit")))
        "a tool the policy denies is denied whatever the memory holds")))

(deftest a-ctx-with-no-memory-behaves-as-the-plain-policy
  (let [asked (atom 0)
        root (srce/root)]
    (srce/provide! root :permissions (permissions/remembering (fn [_ _] :ask)))
    (srce/provide! root :approvals
                   (permissions/approver
                    (fn [_ _] (swap! asked inc) {:decision :allow :scope :agent})))
    (is (nil? (permissions/check root bash-call)))
    (is (nil? (permissions/check root bash-call)))
    (is (= 2 @asked) "nothing stores the decision, so both calls ask")))

(deftest the-check-is-the-points-innermost-next
  ;; the tool point (kmet.app.tools.invoke/tool-point) runs the chain outside
  ;; the permission check: every hook settles the op first, and only then does
  ;; the policy judge the effective call
  (testing "the policy judges the rewritten arguments"
    (let [seen (atom nil)
          root (srce/root)
          chain (hooks/around [(fn [op next] (next (assoc op :args {"command" "ls -la"})))])]
      (srce/provide! root :permissions
                     (permissions/policy (fn [_ request] (reset! seen (:args request)) :allow)))
      (is (= {:content "ran"}
             (invoke/tool-point {:chain chain
                                 :check (fn [op] (permissions/check root op))
                                 :execute (fn [_] {:content "ran"})}
                                bash-call))
          "the rewritten call reaches the body")
      (is (= {"command" "ls -la"} @seen) "the policy saw the effective call")))
  (testing "a hook that settles the call never consults the policy"
    (let [consulted (atom false)
          root (srce/root)
          chain (hooks/around [(fn [_op _next] {:block true :reason "hook"})])]
      (srce/provide! root :permissions
                     (permissions/policy (fn [_ _] (reset! consulted true) :allow)))
      (is (= {:content "hook" :is-error true}
             (invoke/tool-point {:chain chain
                                 :check (fn [op] (permissions/check root op))
                                 :execute (fn [_] {:content "ran"})}
                                bash-call)))
      (is (false? @consulted))))
  (testing "a hook may clear the arguments, and the policy sees that call"
    (let [seen (atom :unread)
          root (srce/root)
          chain (hooks/around [(fn [op next] (next (assoc op :args nil)))])]
      (srce/provide! root :permissions
                     (permissions/policy (fn [_ request] (reset! seen (:args request)) :allow)))
      (is (= {:content "ran"}
             (invoke/tool-point {:chain chain
                                 :check (fn [op] (permissions/check root op))
                                 :execute (fn [_] {:content "ran"})}
                                bash-call)))
      (is (nil? @seen) "the policy judged the call with no arguments")))
  (testing "no hook and an allowing policy runs the body"
    (is (= {:content "ran"}
           (invoke/tool-point {:check (fn [op] (permissions/check (srce/root) op))
                               :execute (fn [_] {:content "ran"})}
                              bash-call)))))

(deftest a-service-that-cannot-answer-fails-when-it-is-built
  (is (thrown? clojure.lang.ExceptionInfo (permissions/policy nil)))
  (is (thrown? clojure.lang.ExceptionInfo (permissions/approver 1)))
  (is (thrown? clojure.lang.ExceptionInfo
               (permissions/policy (fn [_ _] :allow) :not-a-fn))))

(deftest the-approval-row-provides-a-denying-seam
  (let [ctx (srce/root)]
    (srce/plugin! ctx permissions/approvals)
    (is (fn? (:ask! (srce/get ctx :approvals))))
    (is (= :deny (:decision ((:ask! (srce/get ctx :approvals)) ctx {})))
        "a mount with no dialog answers no")
    (is (nil? (srce/get ctx :permissions))
        "the row does not install a policy: deciding is per agent")))

(deftest the-policy-row-carries-a-config
  (let [ctx (srce/root)]
    (srce/plugin! ctx permissions/permissions {:policy (fn [_ _] :deny)})
    (is (= {:block true :reason "denied by the permission policy"}
           (permissions/check ctx bash-call))
        "a preset mounts the row with its policy")
    (is (fn? (:remember! (srce/get ctx :permissions)))
        "the row's policy remembers what an approver scopes")
    (is (nil? (srce/get ctx permissions/memory-key))
        "but the memory is the agent's: the row mounts no store of its own")))
