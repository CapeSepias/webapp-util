# Scala Webapp Utilities
[![Build Status](https://travis-ci.org/japgolly/webapp-util.svg?branch=master)](https://travis-ci.org/japgolly/webapp-util)
[![Latest Version](https://maven-badges.herokuapp.com/maven-central/com.github.japgolly.webapp-util/protocol_2.13/badge.svg)](https://maven-badges.herokuapp.com/maven-central/com.github.japgolly.webapp-util/protocol_2.13)

This library was initially extracted (with permission) from the closed-source [ShipReq](https://blog.shipreq.com/about/)
where it when through many evolutions, and was battled-tested on a real-world, large and complex project.
It was ported without git commit history, so please understand that in this case,
the low commit count is not an indication of immaturity.

```scala
val WebappUtilVer = "<version>"

"com.github.japgolly.webapp-util" %%% "protocol"            % WebappUtilVer
"com.github.japgolly.webapp-util" %%% "protocol-test"       % WebappUtilVer % Test
"com.github.japgolly.webapp-util" %%% "protocol-circe"      % WebappUtilVer
"com.github.japgolly.webapp-util" %%% "protocol-circe-test" % WebappUtilVer % Test
```


# Included

* The `protocol` module:

  * `japgolly.webapputil.protocol.general`
    * `ErrorMsg` - typed error message, with some util and predefined cases
    * `EscapeUtil` - functions for escaping strings
    * `Protocol` - abstract definitions of protocols
    * `ServerSideProcInvoker` - abstract and invokable representation of a server-side procedure *(JS only)*
    * `Url` - types for URLs

  * `japgolly.webapputil.protocol.ajax`
    * `AjaxProtocol` - protocol for an AJAX endpoint
    * `AjaxClient` - means for a client to perform AJAX calls *(JS only)*

  * `japgolly.webapputil.protocol.binary`
    * `BinaryData` - immutable representation of BinaryData
    * `BinaryJs` - functions for conversion between various JS binary data types *(JS only)*

  * `japgolly.webapputil.protocol.entrypoint`
    * `EntrypointDef` - definition of a JS app entrypoint
    * `Entrypoint` - abstract class for a JS app entrypoint *(JS only)*
    * `EntrypointInvoker` - generate JS to invoke an entrypoint *(JVM only)*
    * `Html` - HTML content *(JVM only)*
    * `Js` - JavaScript code (and some utilities) *(JVM only)*
    * `LoadJs` - define a bundle of JS assets to be loaded via `loadjs` before entrypoint invocation *(JVM only)*

* The `protocol-test` module:
  * `japgolly.webapputil.protocol.test`
    * `BinaryTestUtil` - utilities for testing binary data
    * `TestAjaxClient` - an `AjaxClient` instance for use in tests *(JS only)*

* The `protocol-circe` module:
  * `japgolly.webapputil.protocol.circe`
    * `JsonCodec` - composition of Circe's `Encoder` and `Decoder` into a single typeclass
    * `JsonEntrypointCodec` - creates instances of `EntrypointDef.Codec` using Circe codecs
    * `JsonUtil` - utilities to supplement Circe

* The `protocol-circe-test` module:
  * `japgolly.webapputil.protocol.circe.test`
    * `JsonTestUtil` - utilities to test JSON codecs


# TODO:

* Add ScalaDoc and proper doc
* Port websocket stuff
* Port webstorage stuff
* Port SafePickler and related


# Support
If you like what I do
—my OSS libraries, my contributions to other OSS libs, [my programming blog](https://japgolly.blogspot.com)—
and you'd like to support me, more content, more lib maintenance, [please become a patron](https://www.patreon.com/japgolly)!
I do all my OSS work unpaid so showing your support will make a big difference.