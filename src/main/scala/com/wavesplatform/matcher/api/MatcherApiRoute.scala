package com.wavesplatform.matcher.api

import javax.ws.rs.Path

import akka.actor.ActorRef
import akka.http.scaladsl.model.{StatusCodes, Uri}
import akka.http.scaladsl.server.{Directive1, Route}
import akka.pattern.ask
import akka.util.Timeout
import com.google.common.primitives.Longs
import com.wavesplatform.matcher.MatcherSettings
import com.wavesplatform.matcher.market.MatcherActor.{GetMarkets, GetMarketsResponse}
import com.wavesplatform.matcher.market.MatcherTransactionWriter.GetTransactionsByOrder
import com.wavesplatform.matcher.market.OrderBookActor._
import com.wavesplatform.matcher.market.OrderHistoryActor._
import vsys.settings.RestAPISettings
import vsys.blockchain.state.reader.StateReader
import io.swagger.annotations._
import play.api.libs.json._
import vsys.account.PublicKeyAccount
import vsys.api.http._
import vsys.utils.crypto.EllipticCurveImpl
import scorex.crypto.encode.Base58
import vsys.blockchain.transaction.assets.exchange.OrderJson._
import vsys.blockchain.transaction.assets.exchange.{AssetPair, Order}
import vsys.utils.NTP
import vsys.wallet.Wallet

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

@Path("/matcher")
@Api(value = "/matcher/")
case class MatcherApiRoute(wallet: Wallet,storedState: StateReader,
                           matcher: ActorRef, orderHistory: ActorRef,
                           txWriter: ActorRef, settings: RestAPISettings, matcherSettings: MatcherSettings) extends ApiRoute {
  private implicit val timeout: Timeout = 5.seconds

  override lazy val route: Route =
    pathPrefix("matcher") {
      matcherPublicKey ~ orderBook ~ place ~ getOrderHistory ~ getAllOrderHistory ~ getTradableBalance ~ orderStatus ~
        historyDelete ~ cancel ~ orderbooks ~ orderBookDelete ~ getTransactionsByOrder
    }

  def withAssetPair(a1: String, a2: String): Directive1[AssetPair] = {
    AssetPair.createAssetPair(a1, a2) match {
      case Success(p) => provide(p)
      case Failure(_) => complete(StatusCodes.BadRequest -> Json.obj("message" -> "Invalid asset pair"))
    }
  }

  @Path("/")
  @ApiOperation(value = "Matcher Public Key", notes = "Get matcher public key", httpMethod = "GET")
  def matcherPublicKey: Route = (pathEndOrSingleSlash & get) {
    complete(wallet.findPrivateKey(matcherSettings.account)
      .map(a => JsString(Base58.encode(a.publicKey)))
      .getOrElse[JsValue](JsString("")))
  }

  @Path("/orderbook/{amountAsset}/{priceAsset}")
  @ApiOperation(value = "Get Order Book for a given Asset Pair",
    notes = "Get Order Book for a given Asset Pair", httpMethod = "GET")
  @ApiImplicitParams(Array(
    new ApiImplicitParam(name = "amountAsset", value = "Amount Asset Id in Pair, or 'VSYS'", dataType = "string", paramType = "path"),
    new ApiImplicitParam(name = "priceAsset", value = "Price Asset Id in Pair, or 'VSYS'", dataType = "string", paramType = "path"),
    new ApiImplicitParam(name = "depth", value = "Limit the number of bid/ask records returned", required = false, dataType = "integer", paramType = "query")
  ))
  def orderBook: Route = (path("orderbook" / Segment / Segment) & get) { (a1, a2) =>
    withAssetPair(a1, a2) { pair =>

      onComplete((matcher ? GetOrderBookRequest(pair, None)).mapTo[MatcherResponse]) {
        case Success(resp) => resp.code match {
          case StatusCodes.Found => redirect(Uri(s"/matcher/orderbook/$a2/$a1"), StatusCodes.Found)
          case code => complete(code -> resp.json)
        }
        case Failure(ex)    => complete(StatusCodes.InternalServerError -> s"An error occurred: ${ex.getMessage}")
      }
    }
  }

  @Path("/orderbook")
  @ApiOperation(value = "Place order",
    notes = "Place a new limit order (buy or sell)",
    httpMethod = "POST",
    produces = "application/json",
    consumes = "application/json")
  @ApiImplicitParams(Array(
    new ApiImplicitParam(
      name = "body",
      value = "Json with data",
      required = true,
      paramType = "body",
      dataType = "vsys.blockchain.transaction.assets.exchange.Order"
    )
  ))
  def place: Route = path("orderbook") {
    (pathEndOrSingleSlash & post) {
      json[Order] { order =>
        (matcher ? order)
          .mapTo[MatcherResponse]
          .map(r => r.code -> r.json)
      }
    }
  }

  @Path("/orderbook/{amountAsset}/{priceAsset}/cancel")
  @ApiOperation(value = "Cancel order",
    notes = "Cancel previously submitted order if it's not already filled completely",
    httpMethod = "POST",
    produces = "application/json",
    consumes = "application/json")
  @ApiImplicitParams(Array(
    new ApiImplicitParam(name = "amountAsset", value = "Amount Asset Id in Pair, or 'VSYS'", dataType = "string", paramType = "path"),
    new ApiImplicitParam(name = "priceAsset", value = "Price Asset Id in Pair, or 'VSYS'", dataType = "string", paramType = "path"),
    new ApiImplicitParam(
      name = "body",
      value = "Json with data",
      required = true,
      paramType = "body",
      dataType = "com.wavesplatform.matcher.api.CancelOrderRequest"
    )
  ))
  def cancel: Route = (path("orderbook" / Segment / Segment / "cancel") & post) { (a1, a2) =>
    withAssetPair(a1, a2) { pair =>
      json[CancelOrderRequest] { req =>
        (matcher ? CancelOrder(pair, req))
          .mapTo[MatcherResponse]
          .map(r => r.code -> r.json)
      }
    }
  }

  @Path("/orderbook/{amountAsset}/{priceAsset}/delete")
  @ApiOperation(value = "Delete Order from History by Id",
    notes = "Delete Order from History by Id if it's in terminal status (Filled, Cancel)",
    httpMethod = "POST",
    produces = "application/json",
    consumes = "application/json")
  @ApiImplicitParams(Array(
    new ApiImplicitParam(name = "amountAsset", value = "Amount Asset Id in Pair, or 'VSYS'", dataType = "string", paramType = "path"),
    new ApiImplicitParam(name = "priceAsset", value = "Price Asset Id in Pair, or 'VSYS'", dataType = "string", paramType = "path"),
    new ApiImplicitParam(
      name = "body",
      value = "Json with data",
      required = true,
      paramType = "body",
      dataType = "com.wavesplatform.matcher.api.CancelOrderRequest"
    )
  ))
  def historyDelete: Route = (path("orderbook" / Segment / Segment / "delete") & post) { (a1, a2) =>
    withAssetPair(a1, a2) { pair =>
      json[CancelOrderRequest] { req =>
        if (req.isSignatureValid) {
          (orderHistory ? DeleteOrderFromHistory(pair, req.senderPublicKey.address,
              Base58.encode(req.orderId), NTP.correctedTime()))
            .mapTo[MatcherResponse]
            .map(r => r.code -> r.json)
        } else {
          StatusCodes.BadRequest -> Json.obj("message" -> "Incorrect signature")
        }
      }
    }
  }

  def checkGetSignature(publicKey: String, timestamp: String, signature: String): Try[String] = {
    Try {
      val pk = Base58.decode(publicKey).get
      val sig = Base58.decode(signature).get
      val ts = timestamp.toLong
      require(math.abs(ts - NTP.correctedTime()).millis < matcherSettings.maxTimestampDiff, "Incorrect timestamp")
      require(EllipticCurveImpl.verify(sig, pk ++ Longs.toByteArray(ts), pk), "Incorrect signature")
      PublicKeyAccount(pk).address
    }
  }

  @Path("/orderbook/{amountAsset}/{priceAsset}/publicKey/{publicKey}")
  @ApiOperation(value = "Order History by Public Key",
    notes = "Get Order History for a given Asset Pair and Public Key",
    httpMethod = "GET")
  @ApiImplicitParams(Array(
    new ApiImplicitParam(name = "amountAsset", value = "Amount Asset Id in Pair, or 'VSYS'", dataType = "string", paramType = "path"),
    new ApiImplicitParam(name = "priceAsset", value = "Price Asset Id in Pair, or 'VSYS'", dataType = "string", paramType = "path"),
    new ApiImplicitParam(name = "publicKey", value = "Public Key", required = true, dataType = "string", paramType = "path"),
    new ApiImplicitParam(name = "Timestamp", value = "Timestamp", required = true, dataType = "integer", paramType = "header"),
    new ApiImplicitParam(name = "Signature", value = "Signature of [Public Key ++ Timestamp] bytes", required = true, dataType = "string", paramType = "header")
  ))
  def getOrderHistory: Route = (path("orderbook" / Segment / Segment / "publicKey" / Segment) & get) { (a1, a2, publicKey) =>
    (headerValueByName("Timestamp") & headerValueByName("Signature")) { (ts, sig) =>
      checkGetSignature(publicKey, ts, sig) match {
        case Success(addr) =>
          withAssetPair(a1, a2) { pair =>
            complete((orderHistory ? GetOrderHistory(pair, addr, NTP.correctedTime()))
              .mapTo[MatcherResponse]
              .map(r => r.code -> r.json))
          }
        case Failure(ex) =>
          complete(StatusCodes.BadRequest -> Json.obj("message" -> ex.getMessage))

      }
    }
  }

  @Path("/orders/{address}")
  @ApiOperation(value = "All Order History by address",
    notes = "Get All Order History for a given address",
    httpMethod = "GET")
  @ApiImplicitParams(Array(
    new ApiImplicitParam(name = "address", value = "Address", dataType = "string", paramType = "path")
   ))
  def getAllOrderHistory: Route = (path("orders" / Segment) & get & withAuth) { addr =>
    implicit val timeout = Timeout(10.seconds)
    complete((orderHistory ? GetAllOrderHistory(addr, NTP.correctedTime()))
      .mapTo[MatcherResponse]
      .map(r => r.code -> r.json))
  }

  @Path("/orderbook/{amountAsset}/{priceAsset}/tradableBalance/{address}")
  @ApiOperation(value = "Tradable balance for Asset Pair",
    notes = "Get Tradable balance for the given Asset Pair",
    httpMethod = "GET")
  @ApiImplicitParams(Array(
    new ApiImplicitParam(name = "amountAsset", value = "Amount Asset Id in Pair, or 'VSYS'", dataType = "string", paramType = "path"),
    new ApiImplicitParam(name = "priceAsset", value = "Price Asset Id in Pair, or 'VSYS'", dataType = "string", paramType = "path"),
    new ApiImplicitParam(name = "address", value = "Account Address", required = true, dataType = "string", paramType = "path")
  ))
  def getTradableBalance: Route = (path("orderbook" / Segment / Segment / "tradableBalance" / Segment) & get) { (a1, a2, address) =>
    withAssetPair(a1, a2) { pair =>
      complete((orderHistory ? GetTradableBalance(pair, address, NTP.correctedTime()))
        .mapTo[MatcherResponse]
        .map(r => r.code -> r.json))
    }
  }

  @Path("/orderbook/{amountAsset}/{priceAsset}/{orderId}")
  @ApiOperation(value = "Order Status",
    notes = "Get Order status for a given Asset Pair during the last 30 days",
    httpMethod = "GET")
  @ApiImplicitParams(Array(
    new ApiImplicitParam(name = "amountAsset", value = "Amount Asset Id in Pair, or 'VSYS'", dataType = "string", paramType = "path"),
    new ApiImplicitParam(name = "priceAsset", value = "Price Asset Id in Pair, or 'VSYS'", dataType = "string", paramType = "path"),
    new ApiImplicitParam(name = "orderId", value = "Order Id", required = true, dataType = "string", paramType = "path")
  ))
  def orderStatus: Route = (path("orderbook" / Segment / Segment / Segment) & get) { (a1, a2, orderId) =>
    withAssetPair(a1, a2) { pair =>
      complete((orderHistory ? GetOrderStatus(pair, orderId, NTP.correctedTime()))
        .mapTo[MatcherResponse]
        .map(r => r.code -> r.json))
    }
  }

  @Path("/orderbook")
  @ApiOperation(value = "Get the open trading markets",
    notes = "Get the open trading markets along with trading pairs meta data", httpMethod = "GET")
  def orderbooks: Route = path("orderbook") {
    (pathEndOrSingleSlash & get) {
      complete((matcher ? GetMarkets)
        .mapTo[GetMarketsResponse]
        .map(r => r.json))
    }
  }

  @Path("/orderbook/{amountAsset}/{priceAsset}")
  @ApiOperation(value = "Remove Order Book for a given Asset Pair",
    notes = "Remove Order Book for a given Asset Pair", httpMethod = "DELETE")
  @ApiImplicitParams(Array(
    new ApiImplicitParam(name = "amountAsset", value = "Amount Asset Id in Pair, or 'VSYS'", dataType = "string", paramType = "path"),
    new ApiImplicitParam(name = "priceAsset", value = "Price Asset Id in Pair, or 'VSYS'", dataType = "string", paramType = "path")
  ))
  def orderBookDelete: Route = (path("orderbook" / Segment / Segment) & delete & withAuth) { (a1, a2) =>
    withAssetPair(a1, a2) { pair =>
      complete((matcher ? DeleteOrderBookRequest(pair))
        .mapTo[MatcherResponse]
        .map(r => r.code -> r.json))
    }
  }

  @Path("/transactions/{orderId}")
  @ApiOperation(value = "Get Exchange Transactions for order",
    notes = "Get all exchange transactions created by DEX on execution of the given order",
    httpMethod = "GET")
  @ApiImplicitParams(Array(
    new ApiImplicitParam(name = "orderId", value = "Order Id", dataType = "string", paramType = "path")
  ))
  def getTransactionsByOrder: Route = (path("transactions" / Segment) & get) { orderId =>
    complete((txWriter ? GetTransactionsByOrder(orderId))
      .mapTo[MatcherResponse]
      .map(r => r.code -> r.json))
  }
}
