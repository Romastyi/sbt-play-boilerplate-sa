package play.boilerplate.api.client.dsl

object Compat extends AbstractCompat {
  override type WSRequest = play.api.libs.ws.WSRequest
  override type WSResponse = play.api.libs.ws.WSResponse
}
