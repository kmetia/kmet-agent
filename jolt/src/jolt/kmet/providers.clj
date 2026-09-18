(ns jolt.kmet.providers
  "kmet's RFC 0014 provider namespace (jolt/README.md).

   EMPTY BY DESIGN: every gap this lib was created for is runtime surface
   now (jolt-bugs.md records the closures) — the
   java.net.http.HttpTimeoutException ctor, the multi-arg java.net.URI
   ctors, ProcessBuilder's File redirects, SocketOutputStream.write(byte[]),
   LinkedBlockingQueue and the Base64 MIME pair all come from the runtime,
   so nothing is claimed and nothing is registered. The scaffolding stays
   because RFC 0014 is how a JDK gap gets filled: the next one adds its
   class to jolt/deps.edn's :jolt/provides and its member registration to
   install! below.

   RSA is not provided here: jolt.crypto supplies it (EC and RSA
   keygen/Signature/KeyFactory) and claims those classes itself — jolt
   allows a class a single provider.

   NO jolt.crypto REQUIRE: this ns needs only jolt.host and clojure.core.
   crypto's classes resolve through crypto's own :jolt/provides claims on
   the first reference, and a declared provider owns the members it
   registers, so no require is needed to pin an order.")

;; ─── install! ─────────────────────────────────────────────────────────────

(defn install!
  "Register everything this lib provides. Idempotent (registrations are
   table merges; members replaced last-wins). Called once at load — the
   final form of this namespace. A no-op while the lib is empty; a member
   of a class the runtime IMPLEMENTS but does not fully supply needs the
   guarded-require convention instead, since jolt refuses a claim on an
   implemented class."
  []
  nil)

(install!)
