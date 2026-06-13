---
id: "3111ca28-bb12-4575-a39c-cdd0b6190280"
title: "On Lisp"
subtitle: "A review of Paul Graham's 1993 book."
published: 2026-04-26
description: |
  A while back I committed some time to studying Paul Graham's 1993 book On
  Lisp.  On Lisp is frequently recommended within Lisp circles as the bible for
  learning macros, which has contributed to the design of several programming
  languages.  Having read the book, I can say that this is indeed true;
  however, many reviews and references miss the central thesis of the book.
keywords: "lisp, design, language, paul graham, book, review, programming, macros, functions, closures, advanced"
og.type: "article"
export: true
---

A while back I committed some time to studying [Paul Graham][]'s 1993 book [_On
Lisp: Advanced Techniques for Common Lisp_][on lisp].  On Lisp is frequently
recommended within Lisp circles as the bible for learning macros, which
influenced the design of several programming languages (including [Clojure][]).
Having read the book, I can say that this is indeed true; however, many reviews
and references to it miss the central thesis of the book.

The underlying theme, and explicit subject of the introductory chapter, is
something Graham calls "[bottom-up programming][botprog]".  We discover that
this is really a book about _programming language design_, or more
specifically, it teaches how to morph your language into one that better suits
the problem domain you are working in.

> Experienced Lisp programmers divide up their programs differently. As well as
> top-down design, they follow a principle which could be called bottom-up
> design—<mark>changing the language to suit the problem</mark>.  In Lisp, you
> don’t just write your program down toward the language, you also build the
> language up toward your program. As you’re writing a program you may think “I
> wish Lisp had such- and-such an operator.” So you go and write it.  Afterward
> you realize that using the new operator would simplify the design of another
> part of the program, and so on. <mark>Language and program evolve
> together.</mark> Like the border between two warring states, the boundary
> between language and program is drawn and redrawn, until eventually it comes
> to rest along the mountains and rivers, the natural frontiers of your
> problem. In the end your program will look as if the language had been
> designed for it. And when language and program fit one another well, you end
> up with code which is clear, small, and efficient.
>
> &mdash; Paul Graham, *On Lisp* &sect; Introduction

"Bottom-up programming" is so foundational to the book that even the title is a
sly reference to it:

> The title is intended to stress the importance of bottom-up programming in
> Lisp. Instead of just writing your program in Lisp, you can write your own
> language ***on Lisp***, and write your program in that.
>
> &mdash; Paul Graham, *On Lisp* &sect; Preface

Despite my reservations about calling it "bottom-up programming", this
technique of pulling the language up to more effectively match the problem
space is absolutely crucial to good software design.  Consider how each
function you define adds a new specialised term to the language.  How the ways
we organise our code, the names we choose and libraries we use shape our
languages.  This *is* language design.  We need to care about and put real
thought into how we are extending the language in which we express our
solutions.  If we don't, it is all too easy to unintentionally make life more
difficult for ourselves through ever more rapidly increasing complexity.

In a talk at OOPSLA '98, [Guy Steele, Jr.][] echoes Graham's views on language
design[^langdes]:

> [A] good programmer in these times, does not just write programs.  <mark>A
> good programmer builds a working vocabulary.  In other words, a good
> programmer does language design.  Though not from scratch, but building on
> the frame of a base language.</mark>
>
> In the course of giving this talk [...] I have had to define 50 or more new
> words or phrases, and 16 names of persons or things.  [...]  In this way, I
> added to the base language.  [...]  It should give no one pause, to note the
> writing of a program having a million lines of code, might need many, many,
> hundreds of new words.  That is to say, a new language built on the base
> language.  I will be so bold to say it cannot be done any other way.
>
> &mdash; Guy L. Steele, Jr., [*Growing a Language*](https://www.youtube.com/watch?v=lw6TaiXzHAE), 1998

Throughout On Lisp, Graham tries to instil this in the reader by explaining and
demonstrating the implementation of several advanced programming concepts atop
Common Lisp, with each successive chapter building more advanced features upon
the creations of prior ones, creating an increasingly abstract language.  This
approach reinforces the lessons of previous chapters while showing how such
features can be used, and essentially gives you two books in one.

Some of the advanced programming techniques and concepts the book covers are:

- functional programming with first-class functions,
- using function closures to encode data (a weird but sometimes useful
  technique for some performance sensitive problems),
- everything you would ever need to know about Lisp-style macros: how to write
  them, when to use them, and how to avoid pitfalls,
- "[anaphoric macros][]" (which first appeared in this book),
- implementing efficient compile time data destructuring with macros,
- compile time compilation of database queries,
- faking continuations and [non-deterministic code][non-determinism] with
  macros (this has come in handy so many times for me in real-world
  situations),
- simulating concurrency/multiple-processes with macros,
- creating a mini embedded Prolog implementation,
- implementing a basic object system (inspired by [CLOS][]).

All the while teaching just enough Common Lisp to follow along and a little bit
of Scheme.

In keeping with Graham's coding style (and other writing), On Lisp is
beautifully concise; extremely so for what it covers.  Within its 400 pages it
shows how such a small amount of code can go a long way through careful,
intentional design.  However, at times this conciseness can be a double-edged
sword.  I found the explanations of continuations to be a little too brief,
requiring additional time to digest, research and experiment to properly
understand them.

On Lisp is one of those rare books that expands your mind to new ways of
thinking about solving problems and expressing solutions.  I can say with
confidence that reading it has made me a *much* better programmer, providing me
with mountains of invaluable knowledge on language design and programming
concepts, many of which I have actually used to great effect in several
real-world production systems.

I went from having only created a couple of small macros to total confidence,
as can be seen in the advanced chaining of macro-defining macros of my [Clojure
logging library][Epilogue].  For a while I played around with non-deterministic
algorithms, replicating the book's "choose" operator in Clojure but instead
[exploiting exceptions and macros][clj-amb] to fake continuations and later
repeating the experiment in Elixir with [list comprehensions and
macros][ex-amb].  This recently helped massively at work as I was able to
identify that a particular problem was a non-determinism one and create an
effective solver for it with a custom made embedded domain-specific language
(EDSL) for testing it.  Those were just a few of *many* examples where On Lisp
has helped me and I'm sure there will be loads more in the future.

I honestly cannot recommend On Lisp enough, but a word of warning: it is not an
easy book.  On Lisp will consume a significant chunk of your time if you want
to truly understand all that it has to offer.  If you do not already know
Common Lisp prior to reading it or won't ever use it, don't let that put you
off, as the lessons are transferable.  Finally, note that there are also a few
known [mistakes][errata] that Graham has shared corrections for online.


## Reconstruction

Sadly, the physical book is long out of print and original copies are rare.
However, if you want a physical copy, you can print one yourself through
[Lulu](https://www.lulu.com).  You will need to select the "perfect paperback"
book type on Lulu and upload these files: [content](on_lisp_content.pdf),
[cover](on_lisp_cover.pdf)[^cover].

This reconstruction has been made possible thanks to the effort of several
people:

- 1993: Paul Graham published _On Lisp_ through Prentice Hall.
- 2002: Paul Graham regained copyright and published a [free download](http://paulgraham.com/onlisptext.html)
  of _On Lisp_ (with missing diagrams).
- 2010: Ramakrishnan shared scans of the [missing diagrams](https://web.archive.org/web/20100302002206/http://www.zerobeat.in/wiki/doku.php?id=onlisp_missing_figures).
- 2015: David Drysdale created SVG versions of the diagrams from the scans,
  constructing a [complete PDF](https://www.lurklurk.org/onlisp/onlisp.html).
- 2021: I (Alex Vear) created a high-resolution, near identical [reproduction of the original cover](on_lisp_cover.pdf)
  compatible with Lulu based on Drysdale's cover recreation.


## Footnotes

[^langdes]: Fun bit of trivia: in 2001 both Graham and Steele were panellists on a
    [dynamic language design panel](https://www.youtube.com/watch?v=agw-wlHGi0E)
    hosted by MIT.  Graham published his notes as the essay
    "[Five Questions about Language Design](https://paulgraham.com/langdes.html)".

[^cover]: The imperfection on the right edge of the cover will be removed when
    the book is cut to size.

[Paul Graham]: https://paulgraham.com
[Guy Steele, Jr.]: https://en.wikipedia.org/wiki/Guy_L._Steele_Jr.
[CLOS]: https://en.wikipedia.org/wiki/Common_Lisp_Object_System
[anaphoric macros]: https://en.wikipedia.org/wiki/Anaphoric_macro
[non-determinism]: https://en.wikipedia.org/wiki/Nondeterministic_programming
[errata]: http://paulgraham.com/onlisperrata.html
[on lisp]: https://paulgraham.com/onlisp.html
[botprog]: https://paulgraham.com/progbot.html
[Clojure]: https://clojure.org
[Epilogue]: https://github.com/b-social/epilogue
[clj-amb]: https://codeberg.org/axvr/archive/src/branch/master/2025/clj-amb
[ex-amb]: https://codeberg.org/axvr/archive/src/branch/master/2026/elixir-amb
