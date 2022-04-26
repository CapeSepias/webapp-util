package japgolly.webapputil.test

import japgolly.scalajs.react.CallbackTo
import japgolly.webapputil.browser.WindowPrompt

final case class TestWindowPrompt() extends WindowPrompt {

  var response: Option[Option[String]] = None
  var nextResponse: Option[Option[String]] = None

  private var _calls = 0
  def calls() = _calls

  def setNextResponse(o: Option[String]): Unit =
    nextResponse = Some(o)

  def setNextResponse(s: String): Unit =
    setNextResponse(Some(s))

  override def apply(message: String, default: String) =
    apply(message)

  override def apply(message: String) =
    CallbackTo {
      _calls += 1
      (nextResponse, response) match {
        case (Some(r), _)    => nextResponse = None; r
        case (None, Some(r)) => r
        case (None, None)    => sys.error("No test response available in TestWindowPrompt")
      }
    }
}
