package japgolly.webapputil.protocol.entrypoint

import java.lang.{StringBuilder => JStringBuilder}

/** Allows for loading resources after the page has been rendered, uses loadjs (https://github.com/muicss/loadjs).
  *
  * This allows resource-heavy pages to load quickly and render "Loading" to the user,
  * before loading and parsing all the resources and initialising the SPA.
  *
  * Usage: Create a [[LoadJs.Bundle]] and pass it to [[EntrypointInvoker]].
  */
object LoadJs {

  final class Resource(val href: String, val integrity: Option[String]) {
    def absoluteUrl: Boolean =
      href contains "://"

    val crossOrigin: Option[String] =
      Option.when(absoluteUrl)("anonymous")
  }

  object Resource {
    def apply(href: String): Resource =
      new Resource(href, None)

    def apply(href: String, integrity: Option[String]): Resource =
      new Resource(href, integrity)
  }

  // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

  object Bundle {
    def apply(rs: Resource*): Bundle =
      new Bundle(rs)
  }

  final class Bundle(resources: Iterable[Resource]) {

    assert(resources.nonEmpty, "Empty bundles aren't yet handled.")

    assert(
      resources.size == resources.toList.map(_.href).toSet.size,
      s"Duplicate hrefs detected in: ${resources.toList.map(_.href).sorted}")

    val jsWrapper: Js.Wrapper = {
      val head: String = {
        // Because this is stored as val and Resources bundles are static, efficiency here doesn't matter
        val sb = new JStringBuilder
        val rs = resources.toArray

        assert(rs.length <= 22) // because terms are a,b,c,… and xyz are reserved
        def term(idx: Int) = ('a' + idx).toChar

        // Create variables for each URL - reduces JS size cos they're referenced more than once
        sb append "var "
        for (i <- rs.indices) {
          if (i != 0) sb append ','
          sb append s"${term(i)}='${rs(i).href}'" // should really escape href
        }
        sb append ';'

        // Determine additional tag attributes per resource
        val extraCfg: Map[Char, List[String]] =
          rs.indices.map { i =>
            var cfg = List.empty[String]
            val r = rs(i)
            r.integrity.foreach(a => cfg ::= s"x.integrity='$a'")
            r.crossOrigin.foreach(a => cfg ::=  s"x.crossOrigin='$a'")
            term(i) -> cfg
          }
            .filter(_._2.nonEmpty)
            .toMap

        sb append s"loadjs([${rs.indices.map(term).mkString(",")}],{"
        if (extraCfg.nonEmpty) {
          sb append "before:function(z,x){switch(z){"
          for ((term, cfg) <- extraCfg) {
            sb append cfg.mkString(s"case $term:", ";", ";break;")
          }
          sb append "}},"
        }
        sb append "async:!1," // Fetch files in parallel and load them in series
        sb append "success:function(){"
        sb.toString
      }

      Js.Wrapper(head, "}})")
    }
  }
}
