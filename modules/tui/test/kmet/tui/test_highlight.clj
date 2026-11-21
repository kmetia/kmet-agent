(ns kmet.tui.test-highlight
  (:require [clojure.test :as t]
            [kmet.tui.highlight :as hl]))

(defn- toks [lang text] (hl/tokenize lang text))

(defn- toks-text [lang text]
  (apply str (map second (toks lang text))))

;; ─── Reconstruction: tokens must concatenate back to the exact source ──────

(def ^:private reconstruction-samples
  [["clojure" "(defn foo [x]\n  ;; comment\n  (* x 2))"]
   ["scheme" "(define (f x) (if (> x 0) x -x))"]
   ["common-lisp" "(defun fact (n) (if (<= n 1) 1 (* n (fact (1- n)))))"]
   ["elisp" "(defun my-fn () (interactive) (message \"hi\"))"]
   ["java" "public class Main {\n  public static void main(String[] args) {\n    int x = 0x1F;\n  }\n}"]
   ["kotlin" "fun main() { val x = listOf(1, 2).map { it * 2 } }"]
   ["javascript" "function f(a) { return a ?? 1; } // note"]
   ["typescript" "interface X { a: string }"]
   ["c" "#include <stdio.h>\nint main() { return 0; }"]
   ["cpp" "int main() { std::cout << \"hi\"; }"]
   ["csharp" "class C { void M() { var x = 1.5e3; } }"]
   ["go" "package main\nfunc main() { x := 10 }"]
   ["rust" "fn main() { let x = 10; }"]
   ["swift" "func f() -> Int { return 1 }"]
   ["dart" "void main() { print('hi'); }"]
   ["python" "def f(x):\n    return x * 2  # comment"]
   ["ruby" "def f(x)\n  x * 2\nend"]
   ["bash" "echo $HOME ${FOO:-bar} # comment"]
   ["json" "{\"a\": 1.5e3, \"b\": [true, null]}"]
   ["yaml" "key: value\n- item\n# comment"]
   ["toml" "[section]\nkey = \"value\""]
   ["ini" "; comment\nkey = value"]
   ["sql" "SELECT * FROM t WHERE x = 1; -- c"]
   ["css" "a { color: red; } /* c */"]
   ["html" "<div class=\"x\"><!-- c -->&amp;</div>"]
   ["xml" "<note><to>Tove</to></note>"]
   ["diff" "--- a/f\n+++ b/f\n@@ -1 +1 @@\n-old\n+new"]
   ["php" "<?php\n$greeting = \"Hello\";\nfunction add($a, $b) {\n  return $a + $b; // sum\n}\n?>"]
   ["scala" "object Main extends App {\n  val x: Int = 1 // c\n}"]
   ["groovy" "def add(a, b) { a + b }"]
   ["objective-c" "#import <Foundation/Foundation.h>\n@interface Foo : NSObject\n@end"]
   ["lua" "local function add(a, b)\n  -- sum\n  return a + b\nend"]
   ["powershell" "function Get-Thing {\n  param($Name)\n  Write-Output $Name # c\n}"]
   ["dockerfile" "FROM node:20\nWORKDIR /app\nCOPY package.json .\nRUN npm install\n# comment"]
   ["hcl" "resource \"aws_instance\" \"web\" {\n  ami = \"ami-123\"\n  # c\n}"]
   ["makefile" "CC = gcc\nall: main.o\n\t$(CC) -o app main.o\n# comment"]
   ["perl" "my $name = shift;\nprint \"Hello, $name\\n\"; # c"]
   ["haskell" "module Main where\nmain :: IO ()\nmain = putStrLn \"hi\" -- c"]
   ["elixir" "defmodule Greeter do\n  @name \"world\"\n  def hello(name), do: \"Hello, #{name}\"\nend"]
   ["erlang" "-module(hello).\n-export([main/0]).\nmain() -> io:format(\"Hi\").\n% comment"]
   ["r" "add <- function(a, b) {\n  a + b # comment\n}"]
   ["matlab" "function y = add(a, b)\n  y = a + b; % comment\nend"]
   ["julia" "function add(a, b)\n  #= sum =#\n  a + b\nend"]
   ["fortran" "program hello\n  integer :: x\n  x = 1 ! comment\nend program"]
   ["fsharp" "let add a b =\n  a + b // comment"]
   ["ocaml" "let add a b =\n  a + b (* comment *)"]
   ["zig" "fn add(a: i32, b: i32) i32 {\n  return a + b; // c\n}"]
   ["nim" "proc add(a, b: int): int =\n  a + b # comment"]
   ["crystal" "def add(a, b)\n  a + b # comment\nend"]
   ["vb" "' comment\nModule Program\n  Sub Main()\n    Dim x As Integer = 1\n  End Sub\nEnd Module"]
   ["cmake" "cmake_minimum_required(VERSION 3.10)\nproject(demo)\nadd_executable(app main.c)"]
   ["prolog" "father(john, mary).\n?- father(X, Y).\n% comment"]
   ["scss" "$color: red;\n.class {\n  color: $color; // c\n}"]
   ["less" "@color: red;\n.class { color: @color; }"]
   ["stylus" "$color = red\n.class\n  color $color"]
   ["fish" "function hello\n  echo $argv\nend"]
   ["markdown" "# Heading\n- item\n> quote\n```\ncode\n```"]])

(t/deftest tokenize-reconstructs-source
  (doseq [[lang text] reconstruction-samples]
    (t/is (= text (toks-text lang text))
          (str lang " tokens must reconstruct text"))))

;; ─── Lisp group ────────────────────────────────────────────────────────────

(t/deftest clojure-tokens
  (t/is (= [[:punctuation "("] [:keyword "defn"] [nil " "]
            [nil "foo"] [nil " "] [:punctuation "["] [nil "x"]
            [:punctuation "]"] [nil " "] [:number "1"] [:punctuation ")"]]
           (toks "clojure" "(defn foo [x] 1)")))
  (t/is (= [[:comment ";; c"]] (toks "clojure" ";; c")))
  (t/is (= [[:string "\"a\\\"b\""]] (toks "clojure" "\"a\\\"b\""))))

(t/deftest lisp-reader-macros
  (t/is (= [[:symbol ":kw"]] (toks "clojure" ":kw")))
  (t/is (= [[:symbol "::kw"]] (toks "clojure" "::kw")))
  (t/is (= [[:string "#\"re\""]] (toks "clojure" "#\"re\"")))
  (t/is (= [[:meta "#("] [nil "+"] [nil " "] [:number "1"]
            [:punctuation ")"]]
           (toks "clojure" "#(+ 1)")))
  (t/is (= [[:meta "#'"] [nil "f"]] (toks "clojure" "#'f")))
  (t/is (= [[:comment "#_ x"]] (toks "clojure" "#_ x"))))

(t/deftest scheme-common-lisp-tokens
  (t/is (= [[:punctuation "("] [:keyword "define"] [nil " "] [:punctuation "("]
            [nil "f"] [nil " "] [nil "x"] [:punctuation ")"] [nil " "]
            [:punctuation "("] [:keyword "if"]]
           (toks "scheme" "(define (f x) (if")))
  (t/is (= [[:comment "#| block |#"]] (toks "common-lisp" "#| block |#")))
  (t/is (= [[:literal "nil"]] (toks "common-lisp" "nil"))))

;; ─── ALGOL group ───────────────────────────────────────────────────────────

(t/deftest algol-tokens
  (t/is (= [[:keyword "int"] [nil " "] [:function "main"] [:punctuation "("]
            [:punctuation ")"] [nil " "] [:punctuation "{"] [nil " "]
            [:comment "// hi"]]
           (toks "c" "int main() { // hi")))
  (t/is (= [[:number "0x1F"] [nil " "] [:operator "+"] [nil " "]
            [:number "1.5e3"] [nil " "] [:operator "=="]]
           (toks "c" "0x1F + 1.5e3 ==")))
  (t/is (= [[:string "\"a\\\"b\""] [nil " "] [:keyword "return"]]
           (toks "java" "\"a\\\"b\" return")))
  (t/is (= [[:keyword "fun"] [nil " "] [:function "main"] [:punctuation "("]
            [:punctuation ")"] [nil " "] [:keyword "val"] [nil " "]
            [:number "1"]]
           (toks "kotlin" "fun main() val 1")))
  (t/is (= [[:meta "#include"] [nil " "] [:operator "<"] [nil "stdio"]
            [:operator ">"]]
           (toks "c" "#include <stdio>"))))

;; ─── Shell group ───────────────────────────────────────────────────────────

(t/deftest shell-tokens
  (t/is (= [[nil "echo"] [nil " "] [:variable "$HOME"] [nil " "]
            [:variable "${FOO:-bar}"] [nil " "] [:comment "# c"]]
           (toks "bash" "echo $HOME ${FOO:-bar} # c")))
  (t/is (= [[:keyword "if"] [nil " "] [:keyword "then"] [nil " "]
            [:keyword "fi"]]
           (toks "bash" "if then fi"))))

;; ─── Data group ────────────────────────────────────────────────────────────

(t/deftest data-tokens
  (t/is (= [[:punctuation "{"] [:string "\"a\""] [:punctuation ":"] [nil " "]
            [:number "1.5e3"] [:punctuation ","] [nil " "] [:string "\"b\""]
            [:punctuation ":"] [nil " "] [:punctuation "["] [:literal "true"]
            [:punctuation ","] [nil " "] [:literal "null"] [:punctuation "]"]
            [:punctuation "}"]]
           (toks "json" "{\"a\": 1.5e3, \"b\": [true, null]}")))
  (t/is (= [[:attr "key"] [:punctuation ":"] [nil " "] [nil "value"]]
           (toks "yaml" "key: value")))
  (t/is (= [[:comment "# c"]] (toks "yaml" "# c")))
  (t/is (= [[:punctuation "["] [:section "section"] [:punctuation "]"]]
           (toks "toml" "[section]")))
  (t/is (= [[:attr "key"] [nil " "] [:punctuation "="] [nil " "]
            [:string "\"value\""]]
           (toks "toml" "key = \"value\""))))

;; ─── SQL ───────────────────────────────────────────────────────────────────

(t/deftest sql-tokens
  (t/is (= [[:keyword "SELECT"] [nil " "] [:operator "*"] [nil " "]
            [:keyword "FROM"] [nil " "] [nil "t"] [nil " "] [:keyword "WHERE"]
            [nil " "] [nil "x"] [nil " "] [:operator "="] [nil " "]
            [:number "1"] [:punctuation ";"]]
           (toks "sql" "SELECT * FROM t WHERE x = 1;")))
  (t/is (= [[:literal "NULL"]] (toks "sql" "NULL"))))

;; ─── Markup ────────────────────────────────────────────────────────────────

(t/deftest markup-tokens
  (t/is (= [[:tag "<"] [:tag "div"] [nil " "] [:attr "class"] [:operator "="]
            [:string "\"x\""] [:tag ">"] [nil "text"] [:tag "<"] [:tag "/"]
            [:tag "div"] [:tag ">"]]
           (toks "html" "<div class=\"x\">text</div>")))
  (t/is (= [[:comment "<!-- c -->"]] (toks "html" "<!-- c -->")))
  (t/is (= [[:name "&amp;"]] (toks "html" "&amp;"))))

;; ─── Diff ──────────────────────────────────────────────────────────────────

(t/deftest diff-tokens
  (t/is (= [[:meta "--- a/f\n"] [:meta "+++ b/f\n"] [:meta "@@ -1 +1 @@\n"]
            [:addition "+new\n"] [:deletion "-old\n"] [nil " context"]]
           (toks "diff" "--- a/f\n+++ b/f\n@@ -1 +1 @@\n+new\n-old\n context"))))

;; ─── Language resolution ───────────────────────────────────────────────────

(t/deftest new-language-spot-checks
  (t/is (= [[:meta "<?php"] [nil "\n"] [:variable "$greeting"]]
           (toks "php" "<?php\n$greeting")))
  (t/is (= [[:variable "$Name"] [nil " "] [:attr "-eq"]]
           (toks "powershell" "$Name -eq")))
  (t/is (= [[:meta "-module"] [:punctuation "("] [nil "hello"]
            [:punctuation ")"] [nil "."] [nil "\n"] [:variable "X"]]
           (toks "erlang" "-module(hello).\nX")))
  (t/is (= [[:comment "#= sum =#"]] (toks "julia" "#= sum =#")))
  (t/is (= [[:comment "# c"]] (toks "julia" "# c")))
  (t/is (= [[:comment "--[[ block ]]"]] (toks "lua" "--[[ block ]]")))
  (t/is (= [[:meta "FROM node:20"]] (toks "dockerfile" "FROM node:20")))
  (t/is (= [[:attr "all"] [:punctuation ":"] [nil " "] [nil "main"]
            [nil "."] [nil "o"]]
           (toks "makefile" "all: main.o")))
  (t/is (= [[:variable "$(CC)"]] (toks "makefile" "$(CC)")))
  (t/is (= [[:attr "ami"] [nil " "] [:punctuation "="] [nil " "]
            [:string "\"ami-1\""]]
           (toks "hcl" "ami = \"ami-1\"")))
  (t/is (= [[:keyword "resource"]] (toks "hcl" "resource")))
  (t/is (= [[:variable "$color"] [:punctuation ":"] [nil " "] [nil "red"]]
           (toks "scss" "$color: red")))
  (t/is (= [[:section "# Heading\n"] [nil ""]] (toks "markdown" "# Heading\n")))
  (t/is (= [[:comment "(* c *)"]] (toks "ocaml" "(* c *)")))
  (t/is (= [[:comment "{-x-}"]] (toks "haskell" "{-x-}")))
  (t/is (= [[:comment "{- c -}"]] (toks "haskell" "{- c -}")))
  (t/is (= [] (toks "clojure" "")))
  (t/is (= [[:operator "|>"] [nil " "] [:keyword "let"]]
           (toks "fsharp" "|> let")))
  (t/is (= [[:keyword "PROGRAM"]] (toks "fortran" "PROGRAM")))
  (t/is (= [[:variable "X"] [:punctuation "."] [nil " "] [:operator "?-"]]
           (toks "prolog" "X. ?-")))
  (t/is (= [[:operator "%>%"] [nil " "] [:literal "TRUE"]]
           (toks "r" "%>% TRUE")))
  (t/is (= [[:keyword "defmodule"] [nil " "] [nil "G"] [nil " "]
            [:keyword "do"]]
           (toks "elixir" "defmodule G do")))
  (t/is (= [[:meta "@name"]] (toks "elixir" "@name")))
  (t/is (= [[:keyword "fn"] [nil " "] [:function "add"] [:punctuation "("]]
           (toks "zig" "fn add(")))
  (t/is (= [[:comment "' c"]] (toks "vb" "' c")))
  (t/is (= [[:keyword "module"] [nil " "] [nil "Main"]]
           (toks "haskell" "module Main")))
  (t/is (= [[:keyword "fun"] [nil " "] [:function "main"] [:punctuation "("]
            [:punctuation ")"] [nil " "] [:keyword "val"]]
           (toks "kotlin" "fun main() val")))
  (t/is (= [[:keyword "local"] [nil " "] [:keyword "function"]]
           (toks "lua" "local function")))
  (t/is (= [[:keyword "cmake_minimum_required"] [:punctuation "("]]
           (toks "cmake" "cmake_minimum_required(")))
  (t/is (= [[:keyword "if"] [nil " "] [:keyword "end"]]
           (toks "fish" "if end")))
  (t/is (= [[:function "hello"] [:punctuation "("]]
           (toks "groovy" "hello("))))

(t/deftest token-boundaries-clean
  (t/is (true? (hl/token-boundaries-clean? "clojure")))
  (t/is (true? (hl/token-boundaries-clean? "python")))
  (t/is (false? (hl/token-boundaries-clean? "html")))
  (t/is (false? (hl/token-boundaries-clean? "diff")))
  (t/is (false? (hl/token-boundaries-clean? "markdown")))
  (t/is (nil? (hl/token-boundaries-clean? "nope"))))

(t/deftest language-resolution
  (t/is (hl/supports-language? "clojure"))
  (t/is (hl/supports-language? "clj"))
  (t/is (hl/supports-language? "bb"))
  (t/is (hl/supports-language? "edn"))
  (t/is (hl/supports-language? "lpy"))
  (t/is (= (toks "clojure" "(defn f [x] (* x 2))") (toks "bb" "(defn f [x] (* x 2))")))
  (t/is (= (toks "clojure" "(defn f [x] (* x 2))") (toks "lpy" "(defn f [x] (* x 2))")))
  (t/is (hl/supports-language? "c++"))
  (t/is (hl/supports-language? "C#"))
  (t/is (hl/supports-language? "Clojure"))
  (t/is (hl/supports-language? "php"))
  (t/is (hl/supports-language? "f#"))
  (t/is (hl/supports-language? "ps1"))
  (t/is (hl/supports-language? "terraform"))
  (t/is (hl/supports-language? "docker"))
  (t/is (hl/supports-language? "gradle"))
  (t/is (hl/supports-language? "sass"))
  (t/is (hl/supports-language? "R"))
  (t/is (not (hl/supports-language? "nope")))
  (t/is (nil? (toks "nope" "x")))
  (t/is (= (toks "bash" "echo hi") (toks "sh" "echo hi"))))
